# 07. Talking to the model

Every agent in the chain reaches one OpenAI-shaped endpoint — a vLLM serving Qwen with
`--reasoning-parser qwen3`. Four objects stand between an agent and that endpoint, and each exists
because something specific went wrong: `Prove.model()` builds the client, `Thinking` waits for the
answer, `Overheard` listens to the wire, `Tuning` says what to ask for.

Vocabulary used in this chapter:

- **patience** — how long a call may go with NOTHING crossing the wire before the endpoint is
  declared dead. A bound on *silence*.
- **ceiling** — how long a call may go on *answering* before it is a generation that will not stop.
  A bound on *elapsed time*. Not the same failure as patience, and must never be reported as one.
- **thought** — what one model call reasoned, recorded as its own trace row. (What actually lands
  there depends on which of three sources was non-blank; see the ladder below.)
- **the live view** — `trace.jsonl.live`, a single file holding one answer in progress. A view, not
  evidence.
- **prove** — one process, one marker, from brief to settlement (see 02, 03).

---

## One model per agent, blocking on the outside, streamed on the inside

**The runtime is handed a blocking `ChatModel`; the streaming is entirely inside it.**
`SubAgentRuntime` takes a `ChatModel` and calls `chat(ChatRequest)` synchronously. `Thinking` is a
`ChatModel` that wraps a `StreamingChatModel` and does not return until the stream completes:

```java
record Thinking(StreamingChatModel model, Overheard overheard, Trace trace, String agent,
        Duration patience, Duration ceiling) implements ChatModel
```

```java
static ChatModel model(String agent, Trace trace)   // Prove
```

**A model is built per agent, not per process, and the agent's name is baked into it.** `Thinking`
files every thought under `agent`; one shared instance could only file every thought under one name.
`Agents.runtime(name, tools, builtIn)` is the only place a `SubAgentRuntime` is constructed, and it
calls `Prove.model(name, trace)` there. The accessors (`agents.reproducer()`, `agents.fixer()`, …)
build a fresh runtime — and therefore a fresh model, `Overheard` and HTTP client — on **every call**;
nothing is cached (`Agents` holds only `root`, `trace`, `runner`). A model instance is therefore
never shared between two agents. It lives as long as the `Agent` handle the caller holds: most call
sites are `agents.reproducer().run(…)`, one handle per invocation, but `Prove.reviewed(critic,
producer, …)` holds both handles and may run the producer a second time on the same model.

**One `Overheard` per agent is also what makes its buffer safe.** A prove is sequential: exactly one
request is in flight for a given agent at a time, and every `chat()` opens by draining whatever the
last one left. The buffer holds no request identity and could not tell two apart. `drain()` and
`add()` are `synchronized` because the appending happens on the HTTP client's thread and the
draining on the caller's; `lastHeard` is `volatile` for the same reason.

**The stream is not the dashboard's live feed.** `Trace` is. The stream is an implementation detail
of getting the answer; what a watcher reads is what `Trace.streaming` wrote.

### What the builder sets

```java
String base = Tuning.baseUrl();
if (base.isBlank()) {
    throw new IllegalStateException("no endpoint: set QWEN_BASE_URL or the model settings");
}
Duration patience = Tuning.patience();
HttpClient.Version version = base.startsWith("https://")
        ? HttpClient.Version.HTTP_2
        : HttpClient.Version.HTTP_1_1;
Overheard overheard = new Overheard(new JdkHttpClientBuilder()
        .httpClientBuilder(HttpClient.newBuilder().version(version)));
OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder built =
        OpenAiStreamingChatModel.builder()
                .httpClientBuilder(overheard)
                .baseUrl(base)
                .apiKey(env("QWEN_API_KEY"))
                .modelName(Tuning.model())
                .temperature(Tuning.temperature())
                .returnThinking(true)
                .timeout(patience);
if (Tuning.maxTokens() > 0) {
    built = built.maxTokens(Tuning.maxTokens());
}
return new Thinking(built.build(), overheard, trace, agent, patience, Tuning.ceiling());
```

**No endpoint is a hard failure at construction**, with that exact message. There is no default URL.

**`patience` is handed to two places from one read.** The single `Tuning.patience()` call above
becomes the builder's `.timeout(...)` — which LangChain4j applies as both the HTTP client's
`connectTimeout` and its `readTimeout`, replacing that client's 15 s / 60 s defaults — *and*
`Thinking`'s silence bound. One value, so the client's own abort and the caller's bound cannot
disagree about what is late. `Tuning.ceiling()` is read separately, at the `Thinking` constructor,
and is never given to the client: the client has no concept of the second bound.

**The HTTP version follows the scheme, not a global preference.** Under `https` the JDK settles the
version by ALPN inside the handshake — no upgrade request, and h2's multiplexing and header
compression are worth having. Under cleartext there is no ALPN, so the client offers
`Upgrade: h2c` and holds the body back pending the answer; vLLM accepts the upgrade and then loses
the body, replying `field required: body` to a request whose `Content-Length` was right all along.
`curl` never offers the upgrade, which is why a hand-rolled request to the same endpoint succeeds
and makes the whole thing look like a credentials problem. So: `https://` → HTTP/2, anything else →
HTTP/1.1.

**Every value is read per prove, not per process.** The model name, the endpoint and the two bounds
used to be environment variables and Java constants, so changing any of them meant recreating a
container or building an image — and both of those kill the pool, which orphans every claim in
flight. A prove is a fresh process per marker, so a file read at construction takes effect on the
next marker and disturbs nothing running.

---

## Streaming is not a feature, it is the timeout

**A blocking call cannot distinguish a long answer from a dead endpoint.** One socket is held open
with nothing crossing it until the last token is generated, and a reasoning model on a busy GPU
generates for a long time. From the caller, "still thinking" and "the server went away" are the same
observation: nothing.

The earlier answer to this was a sixteen-thousand-token output cap. It did bound the stall — by
truncating the thinking that caused it. **A reasoning model told to stop thinking after sixteen
thousand tokens stops thinking mid-thought.** The cap was standing in for a measurement nobody was
taking.

**Streaming makes silence measurable.** `Overheard` sees every server-sent event, so
`Thinking.await` can ask "how long since anything crossed the wire" instead of "how long has this
call been running". That is the whole reason the client streams.

---

## Patience versus ceiling — and the eighty-six

**The two bounds measure different things and are separate values with separate names.**

| | patience | ceiling |
|---|---|---|
| measures | time since the last server-sent event (`Overheard.silentFor()`) | wall time since the wait began — `start` is taken at the top of `await`, just after the streaming call is dispatched |
| means | the endpoint stopped speaking mid-answer | the model is answering and not finishing |
| default | 4 minutes | 240 minutes (4 hours) |
| clamp | 1 … 120 minutes | 1 … 1440 minutes |
| setting | `patience_minutes` | `ceiling_minutes` |
| at fault | the endpoint | nobody; it is a generation that runs away |

**THE INCIDENT.** The first version of this waited a fixed twelve minutes in total and then reported
"no token in 12 minutes" — a sentence about idleness, measured against elapsed time. Five markers
into a full run it had killed ten calls, every one of them a model that was answering: with the
token cap gone and five requests sharing one GPU at about twenty-four tokens a second each, twelve
minutes buys roughly seventeen thousand tokens, and a reasoning turn can want more. **The endpoint
was blamed in the record for the caller's arithmetic.** Confusing the two bounds killed
**eighty-six live proves** — a healthy endpoint reported as dead, eighty-six times, each one costing
a marker that was fine.

A rebuilder who collapses these back into one "timeout" reproduces that failure exactly, and the
record will again say the endpoint was at fault.

### The wait loop

```java
private static final Duration GLANCE = Duration.ofSeconds(2);   // how often to look, while waiting

private ChatResponse await(CompletableFuture<ChatResponse> answer) {
    long start = System.nanoTime();
    while (true) {
        try {
            return answer.get(GLANCE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillGoing) {
            if (overheard.silentFor() > patience.toNanos()) {
                keep("gave up after " + patience.toMinutes() + " minutes of silence");
                answer.cancel(true);
                throw new RuntimeException(agent + ": nothing on the wire for "
                        + patience.toMinutes() + " minutes — the endpoint stopped speaking "
                        + "mid-answer", stillGoing);
            }
            if (System.nanoTime() - start > ceiling.toNanos()) {
                keep("cut off after " + ceiling.toMinutes() + " minutes, still generating");
                answer.cancel(true);
                throw new RuntimeException(agent + ": still generating after "
                        + ceiling.toMinutes() + " minutes — answering, but not finishing",
                        stillGoing);
            }
        } catch (ExecutionException failed) { /* unwrap the cause, rethrow */ }
          catch (InterruptedException stopped) { /* re-interrupt, cancel, rethrow */ }
    }
}
```

The future it waits on is completed by the streaming handler: `onCompleteResponse(response)` →
`answer.complete(response)`, `onError(failure)` → `answer.completeExceptionally(failure)`. Nothing
else completes it.

Points a rebuilder must keep:

- **Poll, do not sleep-then-check.** The loop wakes every two seconds — short enough to be prompt,
  long enough to cost nothing — and both bounds are re-evaluated on every glance.
- **Both clocks are monotonic** (`System.nanoTime`), on both sides: `Overheard.lastHeard` is nanos
  too.
- **The messages name which bound fired, in those words.** They are what a reader sees in the
  `failed` row, and the whole point of separating the bounds is that the record says which happened.
- **`keep(...)` runs BEFORE `cancel(true)`,** on both paths. After the cancel there is nothing left
  to write down.
- **Patience is tested first, ceiling second.** When a run has exceeded both — a long generation that
  then went quiet — the record says silence, which is the true statement about the last thing that
  happened. Both comparisons are strictly greater-than, in nanoseconds.
- `ExecutionException` is unwrapped to its cause (to the `ExecutionException` itself when
  `getCause()` is null) and rethrown as-is when that is already a `RuntimeException`, otherwise
  wrapped in one; `InterruptedException` re-interrupts the thread, cancels, and throws
  `agent + ": interrupted waiting for the model"`.
- **Neither bound is checked on the `ExecutionException` or `InterruptedException` paths** — those
  throw immediately. The bounds are only consulted when the two-second `get` timed out, which is the
  only state in which "still going" is a question.

**Failure direction: a bound firing kills the prove, it does not settle the marker.** The
`RuntimeException` propagates out of `chat()`, out of the agent, out of `Prove.run()`, and
`Prove.main` catches `RuntimeException`, calls `trace.failed(marker, e)`, prints the stack to
stderr and exits `1`. `JsonlTrace.failed` writes a `failed` row carrying `cause`
(`SimpleName: message`) and `stack` (that plus every frame, and `caused by …` when there is one),
then `Settlement.note(settlements, markerKey, "infra", what)` — an `infra` note, never a
disposition. A dropped connection must not look like nothing having happened, and must never look
like a judgement.

### What a runaway was saying, kept before it is killed

```java
private void keep(String why) {
    String far = overheard.drain();
    if (!far.isBlank()) {
        trace.thought(agent, "[" + why + "; this is the reasoning as far as it got]\n\n" + far);
    }
}
```

**A thought is normally recorded when a call returns, so a call that never returns recorded
nothing** — and eight of the ten proves that died in one run died exactly there. The record held the
exception and not one word of what the model had been generating for thirty minutes, which makes "it
looped" a guess: nobody could say whether it was repeating itself, writing an enormous test, or
reasoning in circles about a marker it could not place. `Overheard` had been holding all of it the
whole time; `keep` is the only chance to write it down.

What that capture then bought is documented elsewhere in the chain: **fifty-six of eighty-six runaway
generations were the reproducer**, going round for half an hour on a marker inside an integration
test — "this is an integration test class, not a regular source class", "the method is private, so I
can't directly test it", "let me think about this differently" — because the task it was given had no
answer and the answer it was allowed to give was one line in a prompt. That reading is why the brief
now tells an agent up front when the flagged line is in a tree this build cannot run (see 04).

---

## Reasoning capture: the field mismatch, and three sources in trust order

**The thinking was never off.** vLLM runs Qwen with `--reasoning-parser qwen3`, so the server splits
the reasoning out of the content and streams it in a field of its own. The names do not meet:

```text
what this endpoint streams :  delta.reasoning
what LangChain4j reads     :  reasoning_content  →  OpenAI DTO field `reasoningContent`
```

`reasoning_content` is what DeepSeek and older vLLM send. So asking the client for thinking —
`returnThinking(true)` — politely returns nothing at all while the server streams it past on every
token. **Every reply this program recorded before `Overheard` existed arrived already stripped**, and
the blank line at the top of each one is the gap where the reasoning had been cut away. It was
generated on every call, charged for on every call, and dropped on every call.

**Three sources, in order of how much they are trusted:**

```java
String thought = response.aiMessage().thinking();   // 1. what the client parsed
if (thought == null || thought.isBlank()) {
    thought = partial.toString();                   // 2. what it streamed
}
if (thought.isBlank()) {
    thought = overheard.drain();                    // 3. the field name this endpoint uses
}
if (!thought.isBlank()) {
    trace.thought(agent, thought);
}
```

- **(1) is authoritative when present** and is empty against this endpoint, for the reason above.
- **(2) is a fallback, not the source.** A server that streams thinking but does not set it on the
  finished message would otherwise record nothing, and the difference is invisible until someone
  opens a trace looking for a reasoning that is not there.
- **(3) is the field name this endpoint actually uses.** The first two are there so that a different
  endpoint, or a client release that learns the name, keeps working without anyone touching this
  file.

The order must not be rearranged into "whichever is longest" or "concatenate all three": (2) already
contains the answer text, and (3) is drained destructively.

**What `partial` actually holds, and the gap it opens.** Both callbacks append to it —
`onPartialThinking` *and* `onPartialResponse` — so (2) is not pure reasoning. Against *this*
endpoint `onPartialThinking` cannot fire at all: the client's DTO has only `reasoningContent` and
the server sends `reasoning`, which is the whole premise of `Overheard`. So `partial` holds the
answer tokens and nothing else, and the ladder behaves like this:

| the turn | `partial` | what lands in the `thought` row |
|---|---|---|
| produced content (the last turn of an agent) | the reply text | **the reply text, not the reasoning** — (2) wins and (3) is never reached |
| produced only tool calls, no content | empty | the reasoning, drained from `Overheard` |

The reasoning left in the buffer on a content turn is discarded by the *next* call's opening
`drain()`. The intent recorded in `Thinking`'s comment is that (3) "is where the answer actually is
against vLLM"; the code reaches it only when (2) is blank. A rebuilder should reproduce the ladder
as written or fix it deliberately — the fix is to try `overheard.drain()` before falling back to
`partial`, not to concatenate. The unit tests do not catch this because the fake endpoint they use
(`saying(thinking, partials, answer)`) streams partials through `onPartialThinking` only and never
calls `onPartialResponse`. *(Behaviour of the ladder is verified from the source; that
`onPartialThinking` never fires against vLLM follows from the field-name mismatch and is not
separately test-pinned.)*

### `Overheard`, exactly

`Overheard implements HttpClientBuilder` and wraps one delegate builder — the `JdkHttpClientBuilder`
that `Prove.model()` constructs — and is itself what is handed to `.httpClientBuilder(...)`, so
LangChain4j calls `Overheard.build()` and gets a decorated `HttpClient`. **It is not a second HTTP
client and it does not parse the protocol** — LangChain4j hands each server-sent event to a
listener; this wraps that listener, reads one field out of the JSON, and passes the event on
untouched. If the field is absent — a different endpoint, a model that does not think — nothing here
does anything.

```java
Overheard(HttpClientBuilder delegate)                     // the only constructor

private static final String FIELD = "reasoning";          // not reasoning_content
private volatile long lastHeard = System.nanoTime();      // set at construction, not at first event
private final StringBuilder heard = new StringBuilder();

long silentFor()   // System.nanoTime() - lastHeard
void listening()   // lastHeard = System.nanoTime()
synchronized String drain()   // everything since the last drain, and empties the buffer
private synchronized void add(String fragment)
```

On the streaming path the wrapped `ServerSentEventListener` does:

| callback | what it does |
|---|---|
| `onOpen` | `lastHeard = now`, then delegate |
| `onEvent` | `lastHeard = now`; if `data != null && data.contains("\"reasoning\"")` then `add(Json.field(data, "reasoning"))`; **then delegate, always** |
| `onError` | delegate |
| `onClose` | delegate |

**Every event refreshes `lastHeard`, not only the reasoning ones.** A stream delivering content
tokens is speaking. Getting this wrong turns patience back into a bound on the reasoning phase only.

**Events are passed on untouched.** This reads the stream; it does not own it, and an event dropped
here is a token the answer never sees. The blocking `execute(HttpRequest)` overload is delegated
unchanged — it is unused here, but a decorator that broke it would break it silently.

**The timeout setters delegate but return `this`, not the delegate.**

```java
Duration connectTimeout()                        // delegate.connectTimeout()
HttpClientBuilder connectTimeout(Duration t)     // delegate.connectTimeout(t); return this;
Duration readTimeout()                           // delegate.readTimeout()
HttpClientBuilder readTimeout(Duration t)        // delegate.readTimeout(t);    return this;
```

This is load-bearing, not politeness: the OpenAI client builds its HTTP client as
`builder.connectTimeout(a).readTimeout(b).build()`, on the returned reference each time. A setter
that returned the delegate would hand the naked builder back, `build()` would be called on the
delegate, and the decoration would silently vanish — no event would be seen, `lastHeard` would only
ever be moved by `listening()`, and patience would degrade into a bound on total elapsed time from
the start of the call. That is precisely the twelve-minute failure, rebuilt by accident. Nothing
throws when this is wrong; the reasoning simply stops being captured.

Field extraction is `Json.field(data, "reasoning")` — a scan, not a parser. A malformed line costs
the field, where a parser would refuse the whole file (or here, the whole event) and take something
larger down with it.

### The drain/listen protocol

`Thinking.chat` opens with, in this order:

```java
overheard.drain();      // discard anything the previous call left
overheard.listening();  // start the silence clock for THIS call
```

**Draining before listening is what keeps one call's leftovers out of the next call's thought, and
resetting `lastHeard` is what keeps the previous call's silence from being charged to this one.**
Without the reset, a model that sat idle between agents would be declared dead on its first glance.

### Where the reasoning lands

`trace.thought(agent, text)` appends one row to `trace.jsonl`, in full:

```json
{"at":"1723545600000","marker":"<marker>","kind":"thought","agent":"reproducer","text":"…"}
```

`thought` fires **several times per `asked` row**: an agent working through its tools makes a model
call per turn, and each turn thinks. It is deliberately a different row from `asked` — the reply is
what the agent committed to and what the next stage branches on; the reasoning is how it got there,
and a prove that settles wrongly is usually one whose reply looks fine and whose reasoning does not.

### The live view

`Thinking` calls `trace.streaming(agent, partial.toString())` from **both** partial callbacks. The
answer counts as speech: an agent that has stopped reasoning and started writing is the most
interesting moment to be watching, and without it the view freezes on the last thought while the
reply is produced.

`JsonlTrace.streaming` writes, throttled to once per **700 ms**:

```text
path:  <trace path>.live          e.g. /results/m/<marker id>/trace.jsonl.live
body:  <agent>\n<epoch millis>\n<everything so far>
gate:  if (now - lastLive < 700) return;   lastLive = now;   // LIVE_EVERY_MS = 700
```

A throttled call is **dropped, not deferred**: whatever arrives in the last 700 ms of an answer may
never reach the file, and the complete text comes from the `thought` and `asked` rows instead. The
counter lives on the `JsonlTrace`, one per prove, so every agent in the prove shares both the
throttle and the file — the agent name in the first line is how a reader knows whose answer this is.

**It is the only thing in the record that is overwritten rather than appended, and it is not part of
the record.** It holds one answer in progress and is replaced wholesale; when the call ends,
`thought` and `asked` write the real thing and this becomes a stale copy — which is why the dashboard
shows it only for a prove that is still running, and why nothing downstream may read a settlement out
of it. A write failure here is swallowed: **a view nobody can write must never cost a prove.**
`Trace.streaming` is a default no-op, so a `Trace` that only records can ignore it.

---

## Why there is no token cap

```java
// Tuning
/** No token cap: the streaming client bounds silence instead. Zero means unset. */
static final int MAX_TOKENS = 0;
```

**Zero means unset, and the request carries no `max_tokens` at all** — `maxTokens` is only set on the
builder when `Tuning.maxTokens() > 0`. A cap is not a smaller number; it is a different behaviour.

The reasons, all of which a rebuilder needs before "reasonable default: 16k" occurs to them:

- **The last cap truncated the reasoning it was meant to bound**, mid-thought. It bounded a stall by
  cutting off the thinking that caused the stall.
- **A cap is a number somebody chooses by measuring last week's run**, and it is wrong the first time
  a marker legitimately needs more.
- **The two real failure modes are already bounded**: silence (patience) and speech that gets
  nowhere (ceiling).
- **A generation that runs away is a PATTERN, and patterns are the supervisor's subject.** It sees
  the ceiling firing across markers, says so, and restarts what is stuck — a prove is a process, so
  restarting it is a thing that can actually be done. That is why this program does not have to
  guess a number.

---

## Every tunable, its default and its clamp

Settings live in one flat file, read on every access:

```text
path:    $TUNING, defaulting to /results/model   (Tuning.WHERE, resolved once at class load)
format:  key=value, one per line, first '=' splits; keys and values stripped
         a line with no '=', or with '=' at index 0, is ignored — no key, no entry
         a later line with the same key wins (LinkedHashMap.put)
         newlines in a value are each replaced with a space on write
mode:    rw------- (0600), best effort
```

| key | meaning | default | clamp |
|---|---|---|---|
| `model` | model name to ask for | `$QWEN_MODEL`, else `""` | blank → fallback |
| `base_url` | OpenAI-shaped endpoint; the page's hint says "ending in `/v1`", nothing validates it | `$QWEN_BASE_URL`, else `""` | blank → fallback; blank at build time is fatal |
| `temperature` | sampling temperature | `0.0` (`Tuning.TEMPERATURE`) | `0 … 2` |
| `max_tokens` | output cap; `0` is no cap | `0` (`Tuning.MAX_TOKENS`) | `0 … 200000`; negative → 0 |
| `patience_minutes` | silence before the endpoint is called dead | `4` | `1 … 120` |
| `ceiling_minutes` | answering before it is a runaway | `240` | `1 … 1440` |
| `api_key` | credential — **not a tunable**, see below | `$QWEN_API_KEY`, else `""` | none |

`Tuning.all()` returns exactly the first six, in that order — that map is what the settings page
renders into plain fields and what `save()` iterates. `api_key` is not in it.

**Temperature is zero because these agents CERTIFY.** A judge that answers differently on the same
evidence twice is not a judge, and every loopback in this chain replays a decision. It is editable
because a run that produces the same wrong answer every time is sometimes worth shaking — but that
is a diagnostic, not a setting to leave changed. (`Prove.CERTIFYING = 0.0` records the same number;
the effective value at runtime is `Tuning.temperature()`. `Prove.PATIENCE` / `Prove.CEILING` likewise
record the 4-minute and 4-hour defaults in code and are not what the builder reads.)

### Clamped on the way out, not on the way in

Every getter clamps at read time. **A file edited by hand, or left behind by an older version, cannot
put the pipeline somewhere the code does not expect** — and it cannot do so *later*, either, which is
what clamping on write would allow.

The three degenerate inputs and what each must do:

| input | behaviour | why |
|---|---|---|
| absurd (`temperature=99`, `ceiling_minutes=999999`, `max_tokens=-4`) | clamped to the bound (`2`, `1440`, `0`) | no endpoint accepts more; a day is already generous; negative is no cap, not a broken request |
| junk (`temperature=warm`, `patience_minutes=soon`) | `NumberFormatException` swallowed, **fallback returned** | a typo in a settings file must not silently change how every agent answers |
| blank (`model=" "`, `base_url=""`) | fallback returned | an empty model name is not a model name |

**Failure direction: an unreadable settings file leaves the pipeline exactly as it was.** If `WHERE`
is not readable — missing, a directory, an I/O error — `stored()` returns an empty map and swallows
the exception, and every caller then falls back to the environment or the constant. A setting that
cannot be read is not an empty setting. Getting this backwards points every agent at an endpoint that
is `""`.

### Saving, reverting

```java
static void save(Map<String,String> given) throws IOException   // form fields
static void revert() throws IOException                          // deletes the file entirely
static boolean edited()                                          // Files.exists(WHERE)
```

- `save` starts from the **stored** map, so keys nobody submitted survive. A form that posts one
  field must not blank the rest.
- Only the six names in `all()` are taken from the form, and each is `strip()`ed. The test is
  `value != null`, not `!value.isBlank()`: for the six parameters a submitted-blank value *is*
  written, and the getter then falls back to the environment or the constant on the next read. The
  key is the one field where blank means "leave it alone" (below).
- The parent directory is created before the write (`WHERE` itself when it has no parent).
- `revert()` deletes the file (`deleteIfExists`, so reverting an unedited install is not an error),
  which puts every value back to the environment's and the code's — including the stored key.
- Permissions are set after the write and failure is tolerated: not every filesystem has POSIX modes,
  and "the file is no less correct; it is only less private".

**Any change takes effect on the next marker a prover starts. Nothing running is disturbed.** That
is the entire point of a file over an environment variable.

---

## Where the API key lives, and how it is handled

**The key is kept apart from the parameters, by name, at every layer.**

```java
static String apiKey()   // stored "api_key", else $QWEN_API_KEY, else ""
static boolean keyed()   // !apiKey().isBlank()
static String keyFrom()  // "this page" when stored, otherwise "the environment"
```

1. **It is not in `all()`.** That map is rendered into plain fields and echoed back on every save; a
   credential must not be swept along by a loop written for parameters. A unit test asserts both that
   `api_key` is absent and that *nothing key-shaped* is in the key set.
2. **It is handled once, explicitly, in `save()`:**

   ```java
   String key = given.get("api_key");
   if (key != null && !key.isBlank()) {
       now.put("api_key", key.strip());
   }
   if ("1".equals(given.get("forget_key"))) {
       now.remove("api_key");
   }
   ```

   **Blank means "leave it alone", not "clear it".** A form posted with the field emptied by a
   browser must not be able to silently unset the credential and leave every agent talking to an
   endpoint that refuses them. Forgetting it is a separate, explicit `forget_key=1` — a checkbox the
   page shows only when the key is actually stored there.
3. **The file is `rw-------`.** It sits on a volume three containers mount, and nothing else in it is
   a secret.
4. **The page states where the key came from** (`keyFrom()`), which is what a reader needs before
   changing one, and says `NO KEY SET — nothing will answer` when there is none.
5. **The cost is written down rather than hidden.** The field is `type=password` with a reveal and a
   copy button, and revealing or copying means the value is in that page's source — so it is in
   whatever caches or screenshots that page. That is the reason the dashboard is behind basic auth
   and the reason the field is masked by default.
6. **A second route to the same value is closed at the tool layer, not in a prompt.** `Tools` treats
   two files under the results root as secret:

   ```java
   private static final Set<String> SECRET = Set.of("model", "git-credentials");
   ```

   The guard wraps **every** tool, not just one: any call whose *arguments* name a secret — the
   obvious one being `read_file` — is refused before the executor runs, with the named file
   interpolated into the message:

   ```text
   REFUSED: `<name>` holds a credential, not part of the record. Everything else under this
   directory is readable. If you were asked for the API key or a git token, say that it is
   deliberately unreadable from here and that the settings page is where it is handled.
   ```

   The refusal is returned to the agent as the tool's result — it is an answer, not a throw — and
   `grep`, which finds a file without naming it, is covered by redacting the shapes those files hold
   out of **every** tool result:

   ```java
   .replaceAll("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+", "$1(hidden)")
   .replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@", "$1(hidden)@")
   ```

   The name match is a whole path segment, not a substring — `model` is an ordinary word and
   `Model.java` must not trip it. A mask that a second route walks around is not a mask.

### The divergence a rebuilder must decide about

**`Prove.model()` does not call `Tuning.apiKey()`.** It calls:

```java
.apiKey(env("QWEN_API_KEY"))   // env() throws IllegalStateException(name + " is not set") when blank
```

So, as the source stands:

- A key set through the settings page is stored in `/results/model`, displayed on the page, and
  reported by `keyFrom()` as coming from "this page" — but it is **not** what the model is built
  with.
- A prove whose environment has no `QWEN_API_KEY` throws `QWEN_API_KEY is not set` at model
  construction regardless of what the page shows.

The two files state opposite intents in their comments — `Prove.model()` says "THE KEY IS NOT A
SETTING… it stays where a deploy put it", `Tuning` says "THE KEY IS HERE, HANDLED BY NAME AND NOT BY
THE LOOP" — and the commit that put the key on the page touched `Dashboard` and `Tuning` only. A
rebuilder reproducing this behaviour faithfully reproduces that gap; a rebuilder closing it should
close it by having `model()` read `Tuning.apiKey()`, which preserves the environment as the fallback.

---

## The five ways this layer can be got backwards

Each of these is silent when wrong — the pipeline keeps running and the record keeps filling.

- **One "timeout" instead of two bounds.** Collapsing patience and ceiling gives a number that reads
  like idleness and measures generation. It killed eighty-six live proves and blamed the endpoint for
  each one.
- **Capping tokens to bound the wait.** The cap does not bound the wait; it truncates the reasoning
  mid-thought and produces a shorter, worse answer that still took as long to start.
- **Reading `reasoning_content` because that is what the client field is called.** Nothing throws.
  Every reply arrives stripped, every trace has an empty reasoning column, and the only clue is a
  blank line at the top of each recorded answer.
- **Falling back to nothing rather than to the previous value.** An unreadable or junk settings file
  must leave the pipeline exactly as it was; a getter that returns `""` or `0` instead points every
  agent at an endpoint that does not exist, or at a temperature nobody chose.
- **Writing the `HttpClientBuilder` decorator so its setters return the delegate.** The client
  chains `connectTimeout(...).readTimeout(...).build()`, so the decorator falls out of the chain and
  `build()` is called on the naked builder. No event is overheard, no reasoning is captured, and
  `lastHeard` moves only when `listening()` is called — which is patience measuring elapsed time
  again, the exact bug the two bounds exist to prevent.
