// @ts-nocheck
'use strict';

// TODO(port): docstring
function stryNS_9fa48() {
  var g = typeof globalThis === 'object' && globalThis && globalThis.Math === Math && globalThis || new Function("return this")();
  var ns = g.__stryker__ || (g.__stryker__ = {});
  if (ns.activeMutant === undefined && g.process && g.process.env && g.process.env.__STRYKER_ACTIVE_MUTANT__) {
    ns.activeMutant = g.process.env.__STRYKER_ACTIVE_MUTANT__;
  }
  function retrieveNS() {
    return ns;
  }
  stryNS_9fa48 = retrieveNS;
  return retrieveNS();
}
stryNS_9fa48();
function stryCov_9fa48() {
  var ns = stryNS_9fa48();
  var cov = ns.mutantCoverage || (ns.mutantCoverage = {
    static: {},
    perTest: {}
  });
  function cover() {
    var c = cov.static;
    if (ns.currentTestId) {
      c = cov.perTest[ns.currentTestId] = cov.perTest[ns.currentTestId] || {};
    }
    var a = arguments;
    for (var i = 0; i < a.length; i++) {
      c[a[i]] = (c[a[i]] || 0) + 1;
    }
  }
  stryCov_9fa48 = cover;
  cover.apply(null, arguments);
}
function stryMutAct_9fa48(id) {
  var ns = stryNS_9fa48();
  function isActive(id) {
    if (ns.activeMutant === id) {
      if (ns.hitCount !== void 0 && ++ns.hitCount > ns.hitLimit) {
        throw new Error('Stryker: Hit count limit reached (' + ns.hitCount + ')');
      }
      return true;
    }
    return false;
  }
  stryMutAct_9fa48 = isActive;
  return isActive(id);
}
async function buildReproduceInput({
  $,
  $json
}) {
  if (stryMutAct_9fa48("0")) {
    {}
  } else {
    stryCov_9fa48("0");
    const j = $('Prep prover').item.json;
    let src = '';
    try {
      if (stryMutAct_9fa48("3")) {
        {}
      } else {
        stryCov_9fa48("3");
        src = Buffer.from((stryMutAct_9fa48("6") ? $json.content && '' : stryMutAct_9fa48("5") ? false : stryMutAct_9fa48("4") ? true : (stryCov_9fa48("4", "5", "6"), $json.content || '')).replace(stryMutAct_9fa48("8") ? /\S/g : (stryCov_9fa48("8"), /\s/g), ''), 'base64').toString('utf8');
      }
    } catch (e) {
      if (stryMutAct_9fa48("12")) {
        {}
      } else {
        stryCov_9fa48("12");
        src = '';
      }
    }
    const SRC_MAX = 300000; // past the largest main-java file in the warm repos (~259k)
    const src_truncated = stryMutAct_9fa48("17") ? src.length <= SRC_MAX : stryMutAct_9fa48("16") ? src.length >= SRC_MAX : stryMutAct_9fa48("15") ? false : stryMutAct_9fa48("14") ? true : (stryCov_9fa48("14", "15", "16", "17"), src.length > SRC_MAX);
    if (stryMutAct_9fa48("19") ? false : stryMutAct_9fa48("18") ? true : (stryCov_9fa48("18", "19"), src_truncated)) src = stryMutAct_9fa48("20") ? src : (stryCov_9fa48("20"), src.slice(0, SRC_MAX));

    // Blank out comments and string/char literals, preserving length AND newlines, so a brace or quote
    // inside them cannot desynchronise the body scan. Offsets stay valid against the original source.
    function mask(s) {
      if (stryMutAct_9fa48("21")) {
        {}
      } else {
        stryCov_9fa48("21");
        return s.replace(stryMutAct_9fa48("25") ? /\/\*[\s\s]*?\*\//g : stryMutAct_9fa48("24") ? /\/\*[\S\S]*?\*\//g : stryMutAct_9fa48("23") ? /\/\*[^\s\S]*?\*\//g : stryMutAct_9fa48("22") ? /\/\*[\s\S]\*\//g : (stryCov_9fa48("22", "23", "24", "25"), /\/\*[\s\S]*?\*\//g), stryMutAct_9fa48("26") ? () => undefined : (stryCov_9fa48("26"), m => m.replace(stryMutAct_9fa48("27") ? /[\n]/g : (stryCov_9fa48("27"), /[^\n]/g), ' '))).replace(stryMutAct_9fa48("30") ? /\/\/[\n]*/g : stryMutAct_9fa48("29") ? /\/\/[^\n]/g : (stryCov_9fa48("29", "30"), /\/\/[^\n]*/g), stryMutAct_9fa48("31") ? () => undefined : (stryCov_9fa48("31"), m => ' '.repeat(m.length))).replace(stryMutAct_9fa48("34") ? /"(\\.|["\\\n])*"/g : stryMutAct_9fa48("33") ? /"(\\.|[^"\\\n])"/g : (stryCov_9fa48("33", "34"), /"(\\.|[^"\\\n])*"/g), stryMutAct_9fa48("35") ? () => undefined : (stryCov_9fa48("35"), m => '"' + ' '.repeat(stryMutAct_9fa48("38") ? Math.min(0, m.length - 2) : (stryCov_9fa48("38"), Math.max(0, stryMutAct_9fa48("39") ? m.length + 2 : (stryCov_9fa48("39"), m.length - 2)))) + '"')).replace(stryMutAct_9fa48("42") ? /'(\\.|['\\\n])*'/g : stryMutAct_9fa48("41") ? /'(\\.|[^'\\\n])'/g : (stryCov_9fa48("41", "42"), /'(\\.|[^'\\\n])*'/g), stryMutAct_9fa48("43") ? () => undefined : (stryCov_9fa48("43"), m => "'" + ' '.repeat(stryMutAct_9fa48("46") ? Math.min(0, m.length - 2) : (stryCov_9fa48("46"), Math.max(0, stryMutAct_9fa48("47") ? m.length + 2 : (stryCov_9fa48("47"), m.length - 2)))) + "'"));
      }
    }

    // -> { name, startLine, endLine, text } for the method containing `line`, or null.
    //
    // The parameter list is scanned with a paren BALANCER, not matched by a regex. The parent's extractor
    // used `\([^;{)]*\)`, which stops at the first `)` — so on a Spring codebase like WebGoat, every method
    // with an annotated parameter (`@Value("${webgoat.user.directory}") final String home`,
    // `@RequestParam("x") String x`) failed to match and its whole body became invisible. Those methods
    // then reported "not inside any method", which reads as drift when it is really a parser gap.
    function enclosingMethod(source, line) {
      if (stryMutAct_9fa48("49")) {
        {}
      } else {
        stryCov_9fa48("49");
        const s = mask(source);
        const skip = new Set(stryMutAct_9fa48("50") ? [] : (stryCov_9fa48("50"), ['if', 'for', 'while', 'switch', 'catch', 'synchronized', 'return', 'new', 'else', 'do', 'try']));
        // matches up to (and including) the method name's opening paren
        const sigRe = stryMutAct_9fa48("87") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\S*)\(/g : stryMutAct_9fa48("86") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s)\(/g : stryMutAct_9fa48("85") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\W$]*)\s*)\(/g : stryMutAct_9fa48("84") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][^\w$]*)\s*)\(/g : stryMutAct_9fa48("83") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$])\s*)\(/g : stryMutAct_9fa48("82") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([^A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("81") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\S([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("80") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\S]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("79") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\W$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("78") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[^\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("77") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("76") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[^ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("75") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n])*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("74") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("73") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[^ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("72") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n])*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("71") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("70") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("69") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("68") ? /(?:^|\n)([ \t]*(?:@[\W$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("67") ? /(?:^|\n)([ \t]*(?:@[^\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("66") ? /(?:^|\n)([ \t]*(?:@[\w$.](?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("65") ? /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("64") ? /(?:^|\n)([^ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("63") ? /(?:^|\n)([ \t](?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : stryMutAct_9fa48("62") ? /(?:\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g : (stryCov_9fa48("62", "63", "64", "65", "66", "67", "68", "69", "70", "71", "72", "73", "74", "75", "76", "77", "78", "79", "80", "81", "82", "83", "84", "85", "86", "87"), /(?:^|\n)([ \t]*(?:@[\w$.]+(?:\([^)]*\))?[ \t\n]*)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)[ \t\n]+)*[\w$.<>\[\],?&\s]+?\s([A-Za-z_$][\w$]*)\s*)\(/g);
        // offset -> 1-based line, without an O(n) scan per lookup
        const nl = stryMutAct_9fa48("88") ? ["Stryker was here"] : (stryCov_9fa48("88"), []);
        for (let i = 0; stryMutAct_9fa48("91") ? i >= source.length : stryMutAct_9fa48("90") ? i <= source.length : stryMutAct_9fa48("89") ? false : (stryCov_9fa48("89", "90", "91"), i < source.length); stryMutAct_9fa48("92") ? i-- : (stryCov_9fa48("92"), i++)) if (stryMutAct_9fa48("95") ? source[i] !== '\n' : stryMutAct_9fa48("94") ? false : stryMutAct_9fa48("93") ? true : (stryCov_9fa48("93", "94", "95"), source[i] === '\n')) nl.push(i);
        const lineOf = off => {
          if (stryMutAct_9fa48("97")) {
            {}
          } else {
            stryCov_9fa48("97");
            let lo = 0,
              hi = nl.length;
            while (stryMutAct_9fa48("100") ? lo >= hi : stryMutAct_9fa48("99") ? lo <= hi : stryMutAct_9fa48("98") ? false : (stryCov_9fa48("98", "99", "100"), lo < hi)) {
              if (stryMutAct_9fa48("101")) {
                {}
              } else {
                stryCov_9fa48("101");
                const mid = (stryMutAct_9fa48("102") ? lo - hi : (stryCov_9fa48("102"), lo + hi)) >> 1;
                if (stryMutAct_9fa48("106") ? nl[mid] >= off : stryMutAct_9fa48("105") ? nl[mid] <= off : stryMutAct_9fa48("104") ? false : stryMutAct_9fa48("103") ? true : (stryCov_9fa48("103", "104", "105", "106"), nl[mid] < off)) lo = stryMutAct_9fa48("107") ? mid - 1 : (stryCov_9fa48("107"), mid + 1);else hi = mid;
              }
            }
            return stryMutAct_9fa48("108") ? lo - 1 : (stryCov_9fa48("108"), lo + 1);
          }
        };
        let m;
        while (stryMutAct_9fa48("110") ? (m = sigRe.exec(s)) === null : stryMutAct_9fa48("109") ? false : (stryCov_9fa48("109", "110"), (m = sigRe.exec(s)) !== null)) {
          if (stryMutAct_9fa48("111")) {
            {}
          } else {
            stryCov_9fa48("111");
            const name = m[2];
            if (stryMutAct_9fa48("113") ? false : stryMutAct_9fa48("112") ? true : (stryCov_9fa48("112", "113"), skip.has(name))) continue;
            // 1) balance the parameter list
            let depth = 0,
              close = stryMutAct_9fa48("114") ? +1 : (stryCov_9fa48("114"), -1);
            for (let i = stryMutAct_9fa48("115") ? sigRe.lastIndex + 1 : (stryCov_9fa48("115"), sigRe.lastIndex - 1); stryMutAct_9fa48("118") ? i >= s.length : stryMutAct_9fa48("117") ? i <= s.length : stryMutAct_9fa48("116") ? false : (stryCov_9fa48("116", "117", "118"), i < s.length); stryMutAct_9fa48("119") ? i-- : (stryCov_9fa48("119"), i++)) {
              if (stryMutAct_9fa48("120")) {
                {}
              } else {
                stryCov_9fa48("120");
                if (stryMutAct_9fa48("123") ? s[i] !== '(' : stryMutAct_9fa48("122") ? false : stryMutAct_9fa48("121") ? true : (stryCov_9fa48("121", "122", "123"), s[i] === '(')) stryMutAct_9fa48("125") ? depth-- : (stryCov_9fa48("125"), depth++);else if (stryMutAct_9fa48("128") ? s[i] !== ')' : stryMutAct_9fa48("127") ? false : stryMutAct_9fa48("126") ? true : (stryCov_9fa48("126", "127", "128"), s[i] === ')')) {
                  if (stryMutAct_9fa48("130")) {
                    {}
                  } else {
                    stryCov_9fa48("130");
                    stryMutAct_9fa48("131") ? depth++ : (stryCov_9fa48("131"), depth--);
                    if (stryMutAct_9fa48("134") ? depth !== 0 : stryMutAct_9fa48("133") ? false : stryMutAct_9fa48("132") ? true : (stryCov_9fa48("132", "133", "134"), depth === 0)) {
                      if (stryMutAct_9fa48("135")) {
                        {}
                      } else {
                        stryCov_9fa48("135");
                        close = i;
                        break;
                      }
                    }
                  }
                }
              }
            }
            if (stryMutAct_9fa48("139") ? close >= 0 : stryMutAct_9fa48("138") ? close <= 0 : stryMutAct_9fa48("137") ? false : stryMutAct_9fa48("136") ? true : (stryCov_9fa48("136", "137", "138", "139"), close < 0)) continue;
            // 2) optional throws clause, then the body's opening brace. No brace = an abstract/interface
            //    declaration or (far more often) an ordinary method CALL that happened to look like a signature.
            let k = stryMutAct_9fa48("140") ? close - 1 : (stryCov_9fa48("140"), close + 1);
            while (stryMutAct_9fa48("142") ? k < s.length || /\s/.test(s[k]) : stryMutAct_9fa48("141") ? false : (stryCov_9fa48("141", "142"), (stryMutAct_9fa48("145") ? k >= s.length : stryMutAct_9fa48("144") ? k <= s.length : stryMutAct_9fa48("143") ? true : (stryCov_9fa48("143", "144", "145"), k < s.length)) && (stryMutAct_9fa48("146") ? /\S/ : (stryCov_9fa48("146"), /\s/)).test(s[k]))) stryMutAct_9fa48("147") ? k-- : (stryCov_9fa48("147"), k++);
            if (stryMutAct_9fa48("150") ? s.endsWith('throws', k) : stryMutAct_9fa48("149") ? false : stryMutAct_9fa48("148") ? true : (stryCov_9fa48("148", "149", "150"), s.startsWith('throws', k))) {
              if (stryMutAct_9fa48("152")) {
                {}
              } else {
                stryCov_9fa48("152");
                while (stryMutAct_9fa48("154") ? k < s.length && s[k] !== '{' || s[k] !== ';' : stryMutAct_9fa48("153") ? false : (stryCov_9fa48("153", "154"), (stryMutAct_9fa48("156") ? k < s.length || s[k] !== '{' : stryMutAct_9fa48("155") ? true : (stryCov_9fa48("155", "156"), (stryMutAct_9fa48("159") ? k >= s.length : stryMutAct_9fa48("158") ? k <= s.length : stryMutAct_9fa48("157") ? true : (stryCov_9fa48("157", "158", "159"), k < s.length)) && (stryMutAct_9fa48("161") ? s[k] === '{' : stryMutAct_9fa48("160") ? true : (stryCov_9fa48("160", "161"), s[k] !== '{')))) && (stryMutAct_9fa48("164") ? s[k] === ';' : stryMutAct_9fa48("163") ? true : (stryCov_9fa48("163", "164"), s[k] !== ';')))) stryMutAct_9fa48("166") ? k-- : (stryCov_9fa48("166"), k++);
              }
            }
            if (stryMutAct_9fa48("169") ? s[k] === '{' : stryMutAct_9fa48("168") ? false : stryMutAct_9fa48("167") ? true : (stryCov_9fa48("167", "168", "169"), s[k] !== '{')) continue;
            // 3) balance the body
            let d2 = 0,
              end = stryMutAct_9fa48("171") ? +1 : (stryCov_9fa48("171"), -1);
            for (let i = k; stryMutAct_9fa48("174") ? i >= s.length : stryMutAct_9fa48("173") ? i <= s.length : stryMutAct_9fa48("172") ? false : (stryCov_9fa48("172", "173", "174"), i < s.length); stryMutAct_9fa48("175") ? i-- : (stryCov_9fa48("175"), i++)) {
              if (stryMutAct_9fa48("176")) {
                {}
              } else {
                stryCov_9fa48("176");
                if (stryMutAct_9fa48("179") ? s[i] !== '{' : stryMutAct_9fa48("178") ? false : stryMutAct_9fa48("177") ? true : (stryCov_9fa48("177", "178", "179"), s[i] === '{')) stryMutAct_9fa48("181") ? d2-- : (stryCov_9fa48("181"), d2++);else if (stryMutAct_9fa48("184") ? s[i] !== '}' : stryMutAct_9fa48("183") ? false : stryMutAct_9fa48("182") ? true : (stryCov_9fa48("182", "183", "184"), s[i] === '}')) {
                  if (stryMutAct_9fa48("186")) {
                    {}
                  } else {
                    stryCov_9fa48("186");
                    stryMutAct_9fa48("187") ? d2++ : (stryCov_9fa48("187"), d2--);
                    if (stryMutAct_9fa48("190") ? d2 !== 0 : stryMutAct_9fa48("189") ? false : stryMutAct_9fa48("188") ? true : (stryCov_9fa48("188", "189", "190"), d2 === 0)) {
                      if (stryMutAct_9fa48("191")) {
                        {}
                      } else {
                        stryCov_9fa48("191");
                        end = stryMutAct_9fa48("192") ? i - 1 : (stryCov_9fa48("192"), i + 1);
                        break;
                      }
                    }
                  }
                }
              }
            }
            if (stryMutAct_9fa48("196") ? end >= 0 : stryMutAct_9fa48("195") ? end <= 0 : stryMutAct_9fa48("194") ? false : stryMutAct_9fa48("193") ? true : (stryCov_9fa48("193", "194", "195", "196"), end < 0)) continue;
            const startLine = lineOf(stryMutAct_9fa48("197") ? m.index - 1 : (stryCov_9fa48("197"), m.index + 1)),
              endLine = lineOf(end);
            if (stryMutAct_9fa48("200") ? line >= startLine || line <= endLine : stryMutAct_9fa48("199") ? false : stryMutAct_9fa48("198") ? true : (stryCov_9fa48("198", "199", "200"), (stryMutAct_9fa48("203") ? line < startLine : stryMutAct_9fa48("202") ? line > startLine : stryMutAct_9fa48("201") ? true : (stryCov_9fa48("201", "202", "203"), line >= startLine)) && (stryMutAct_9fa48("206") ? line > endLine : stryMutAct_9fa48("205") ? line < endLine : stryMutAct_9fa48("204") ? true : (stryCov_9fa48("204", "205", "206"), line <= endLine)))) {
              if (stryMutAct_9fa48("207")) {
                {}
              } else {
                stryCov_9fa48("207");
                return stryMutAct_9fa48("208") ? {} : (stryCov_9fa48("208"), {
                  name,
                  startLine,
                  endLine,
                  text: stryMutAct_9fa48("209") ? source : (stryCov_9fa48("209"), source.slice(m.index, end))
                });
              }
            }
            sigRe.lastIndex = end;
          }
        }
        return null;
      }
    }
    const lines = src.split('\n');
    const svLine = stryMutAct_9fa48("213") ? Number(j.svace_line) && 0 : stryMutAct_9fa48("212") ? false : stryMutAct_9fa48("211") ? true : (stryCov_9fa48("211", "212", "213"), Number(j.svace_line) || 0);
    let anchor = '',
      anchor_status = 'unresolved',
      anchor_note = '',
      method_text = '',
      line_text = '';
    if (stryMutAct_9fa48("221") ? false : stryMutAct_9fa48("220") ? true : stryMutAct_9fa48("219") ? src.trim() : (stryCov_9fa48("219", "220", "221"), !(stryMutAct_9fa48("222") ? src : (stryCov_9fa48("222"), src.trim())))) {
      if (stryMutAct_9fa48("223")) {
        {}
      } else {
        stryCov_9fa48("223");
        anchor_note = 'source file could not be fetched';
      }
    } else if (stryMutAct_9fa48("227") ? svLine < 1 && svLine > lines.length : stryMutAct_9fa48("226") ? false : stryMutAct_9fa48("225") ? true : (stryCov_9fa48("225", "226", "227"), (stryMutAct_9fa48("230") ? svLine >= 1 : stryMutAct_9fa48("229") ? svLine <= 1 : stryMutAct_9fa48("228") ? false : (stryCov_9fa48("228", "229", "230"), svLine < 1)) || (stryMutAct_9fa48("233") ? svLine <= lines.length : stryMutAct_9fa48("232") ? svLine >= lines.length : stryMutAct_9fa48("231") ? false : (stryCov_9fa48("231", "232", "233"), svLine > lines.length)))) {
      if (stryMutAct_9fa48("234")) {
        {}
      } else {
        stryCov_9fa48("234");
        // The file got SHORTER than the marker's line: the drift is proven, not merely suspected.
        anchor_status = 'unresolved';
        anchor_note = 'line ' + svLine + ' is past the end of the file as checked out (' + lines.length + ' lines) — the file changed since the scan';
      }
    } else {
      if (stryMutAct_9fa48("239")) {
        {}
      } else {
        stryCov_9fa48("239");
        line_text = lines[stryMutAct_9fa48("240") ? svLine + 1 : (stryCov_9fa48("240"), svLine - 1)];
        const em = enclosingMethod(src, svLine);
        if (stryMutAct_9fa48("242") ? false : stryMutAct_9fa48("241") ? true : (stryCov_9fa48("241", "242"), em)) {
          if (stryMutAct_9fa48("243")) {
            {}
          } else {
            stryCov_9fa48("243");
            anchor = em.name;
            anchor_status = 'exact';
            method_text = em.text;
            anchor_note = 'line ' + svLine + ' falls inside ' + em.name + '() (lines ' + em.startLine + '-' + em.endLine + ')';
          }
        } else {
          if (stryMutAct_9fa48("250")) {
            {}
          } else {
            stryCov_9fa48("250");
            // Every remaining unanchored marker in the WebGoat report lands on a field or a Lombok annotation.
            // That is not drift and not a parser gap: Svace analysed the COMPILED code, where Lombok had
            // already generated the getter/setter/constructor it is complaining about. There is no source
            // method to point at, and an agent told only "not inside any method" will conclude the marker is
            // stale and wrongly clear it. Name the real situation instead.
            anchor_status = 'no-method';
            const lombok = /@(Getter|Setter|Data|Value|AllArgsConstructor|RequiredArgsConstructor|NoArgsConstructor|Builder|With)\b/.test(src);
            anchor_note = 'line ' + svLine + ' is not inside any method body (it is a field, annotation or import)' + (lombok ? ' — and this class uses Lombok, so the accessor or constructor the checker flagged is GENERATED at compile time and has no source form. Settle the claim against the generated API (for example the getter for this field), not against the annotation.' : '');
          }
        }
      }
    }
    const loc = j.file + ':' + svLine;
    const agent_input = (stryMutAct_9fa48("257") ? "Repository: " + j.repo + "   (branch " + j.branch + ", module '" + j.module + "')\n" + "Source file: " + j.file + "\n\n" + "SVACE MARKER  [" + (j.svace_severity || '?') + "]  " + (j.svace_checker || '?') + "\n" + "Location as reported: " + loc + "\n" + "The checker's claim: " + (j.description || '') + "\n\n" + "LOCATION CONFIDENCE: " + anchor_status + " — " + anchor_note + "\n" + (line_text ? "Line " + svLine + " as it reads in the checked-out tree:\n```java\n" + line_text + "\n```\n" : "") + (method_text ? "\nThe enclosing method (this is where the claim should be settled):\n```java\n" + method_text + "\n```\n" : "\nNo enclosing method could be resolved — locate the construct the checker describes yourself.\n") + "\nWrite the proof test in package `" + j.pkg + "`, class `" + j.test_class + "`, at path `" + j.test_path + "`.\n" + "Only write the FAILING test — do not fix the defect.\n\n" + (src_truncated ? "SOURCE FILE (TRUNCATED — you are NOT seeing the whole file):\n```java\n" : "FULL SOURCE FILE:\n```java\n") - src : (stryCov_9fa48("257"), (stryMutAct_9fa48("258") ? "Repository: " + j.repo + "   (branch " + j.branch + ", module '" + j.module + "')\n" + "Source file: " + j.file + "\n\n" + "SVACE MARKER  [" + (j.svace_severity || '?') + "]  " + (j.svace_checker || '?') + "\n" + "Location as reported: " + loc + "\n" + "The checker's claim: " + (j.description || '') + "\n\n" + "LOCATION CONFIDENCE: " + anchor_status + " — " + anchor_note + "\n" + (line_text ? "Line " + svLine + " as it reads in the checked-out tree:\n```java\n" + line_text + "\n```\n" : "") - (method_text ? "\nThe enclosing method (this is where the claim should be settled):\n```java\n" + method_text + "\n```\n" : "\nNo enclosing method could be resolved — locate the construct the checker describes yourself.\n") : (stryCov_9fa48("258"), "Repository: " + j.repo + "   (branch " + j.branch + ", module '" + j.module + "')\n" + "Source file: " + j.file + "\n\n" + "SVACE MARKER  [" + (stryMutAct_9fa48("268") ? j.svace_severity && '?' : stryMutAct_9fa48("267") ? false : stryMutAct_9fa48("266") ? true : (stryCov_9fa48("266", "267", "268"), j.svace_severity || '?')) + "]  " + (stryMutAct_9fa48("273") ? j.svace_checker && '?' : stryMutAct_9fa48("272") ? false : stryMutAct_9fa48("271") ? true : (stryCov_9fa48("271", "272", "273"), j.svace_checker || '?')) + "\n" + "Location as reported: " + loc + "\n" + "The checker's claim: " + (stryMutAct_9fa48("281") ? j.description && '' : stryMutAct_9fa48("280") ? false : stryMutAct_9fa48("279") ? true : (stryCov_9fa48("279", "280", "281"), j.description || '')) + "\n\n" + "LOCATION CONFIDENCE: " + anchor_status + " — " + anchor_note + "\n" + (line_text ? "Line " + svLine + " as it reads in the checked-out tree:\n```java\n" + line_text + "\n```\n" : "") + (method_text ? "\nThe enclosing method (this is where the claim should be settled):\n```java\n" + method_text + "\n```\n" : "\nNo enclosing method could be resolved — locate the construct the checker describes yourself.\n"))) + "\nWrite the proof test in package `" + j.pkg + "`, class `" + j.test_class + "`, at path `" + j.test_path + "`.\n" + "Only write the FAILING test — do not fix the defect.\n\n" + (src_truncated ? "SOURCE FILE (TRUNCATED — you are NOT seeing the whole file):\n```java\n" : "FULL SOURCE FILE:\n```java\n") + src)) + "\n```";
    return stryMutAct_9fa48("302") ? {} : (stryCov_9fa48("302"), {
      ...j,
      src,
      src_truncated,
      agent_input,
      anchor,
      anchor_status,
      anchor_note,
      line_text,
      method_text
    });
  }
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = stryMutAct_9fa48("303") ? {} : (stryCov_9fa48("303"), {
  buildReproduceInput
});