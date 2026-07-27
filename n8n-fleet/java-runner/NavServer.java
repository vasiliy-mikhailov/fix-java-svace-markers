import com.sun.net.httpserver.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.*;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Code-navigation server for the bug-hunter: semantic "go to definition" (Ctrl+Click) + "find
 * implementations", backed by JavaParser's SymbolSolver (source roots + JDK reflection). Per-repo
 * cached; degrades to ranked candidate declarations when a call can't be resolved.
 *   POST /definition      {repoDir,file,line,symbol}
 *   POST /implementations {repoDir,type,method}
 *   GET  /health
 */
public class NavServer {
    static final int SRC_CAP = 16000;

    static class Repo {
        Path dir;
        JavaParser parser;
        JavaSymbolSolver symbolSolver;
        List<CompilationUnit> allCus;   // lazily parsed on first /implementations
    }
    static final Map<String, Repo> REPOS = new HashMap<>();

    static synchronized Repo getRepo(String repoDir) {
        Repo r = REPOS.get(repoDir);
        if (r != null) return r;
        r = new Repo();
        r.dir = Paths.get(repoDir);
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new ReflectionTypeSolver(false));   // JDK types
        Set<Path> roots = new LinkedHashSet<>();
        try (Stream<Path> w = Files.walk(r.dir)) {
            w.filter(Files::isDirectory).forEach(d -> {
                String s = d.toString().replace('\\', '/');
                if (s.endsWith("/src/main/java") || s.endsWith("/src/test/java")) roots.add(d);
            });
        } catch (Exception e) { /* ignore */ }
        if (roots.isEmpty()) roots.add(r.dir);
        for (Path root : roots) {
            try { ts.add(new JavaParserTypeSolver(root.toFile())); } catch (Exception e) { /* ignore */ }
        }
        r.symbolSolver = new JavaSymbolSolver(ts);
        ParserConfiguration cfg = new ParserConfiguration().setSymbolResolver(r.symbolSolver);
        r.parser = new JavaParser(cfg);
        REPOS.put(repoDir, r);
        return r;
    }

    static CompilationUnit parseFile(Repo r, Path fp) {
        try {
            synchronized (r) { return r.parser.parse(fp).getResult().orElse(null); }
        } catch (Exception e) { return null; }
    }

    // ---- go to definition -------------------------------------------------------------------
    static String definition(String body) {
        String repoDir = jstr(body, "repoDir"), file = jstr(body, "file"), symbol = jstr(body, "symbol");
        int line = jint(body, "line");
        int arity = body.contains("\"arity\"") ? jint(body, "arity") : -1;   // -1 = unspecified
        String qualifier = jstr(body, "qualifier");   // package/owner prefix, e.g. opennlp.tools.chunker
        String params = jstr(body, "params");         // "List,List,List" — arity alone cannot separate
                                                      // ChunkSample(List,List,List) from (String[],String[],String[])
        Repo r = getRepo(repoDir);
        Path fp = r.dir.resolve(file);
        if (!Files.isRegularFile(fp)) return err("file not found: " + file);
        CompilationUnit cu = parseFile(r, fp);
        if (cu == null) return err("could not parse " + file);
        cu.setData(Node.SYMBOL_RESOLVER_KEY, r.symbolSolver);   // ensure .resolve() has the solver attached

        // gather references matching symbol, pick the one nearest the given line
        List<Node> cands = new ArrayList<>();
        for (MethodCallExpr e : cu.findAll(MethodCallExpr.class))
            if (nameMatches(e.getNameAsString(), symbol)) cands.add(e);
        for (ObjectCreationExpr e : cu.findAll(ObjectCreationExpr.class))
            if (symbol.isEmpty() || nameMatches(typeName(e.getType()), symbol)) cands.add(e);
        for (ClassOrInterfaceType e : cu.findAll(ClassOrInterfaceType.class))
            if (nameMatches(e.getNameAsString(), symbol)) cands.add(e);
        for (NameExpr e : cu.findAll(NameExpr.class))           // bare identifiers: fields, static-imported constants, vars
            if (nameMatches(e.getNameAsString(), symbol)) cands.add(e);
        for (FieldAccessExpr e : cu.findAll(FieldAccessExpr.class))
            if (nameMatches(e.getNameAsString(), symbol)) cands.add(e);
        if (cands.isEmpty()) return fallback(r, symbol, "no reference named '" + symbol + "' found in " + file, arity, params, file, qualifier);

        // Overloads and same-named types in different packages are DIFFERENT edges of the call graph.
        // Picking "the reference nearest line 0" (i.e. the earliest in the file) silently resolved a
        // 3-arg constructor call as if it were the 3-List one. Narrow by argument count first.
        if (arity >= 0) {
            List<Node> byArity = new ArrayList<>();
            for (Node n : cands) {
                int ac = -1;
                if (n instanceof MethodCallExpr) ac = ((MethodCallExpr) n).getArguments().size();
                else if (n instanceof ObjectCreationExpr) ac = ((ObjectCreationExpr) n).getArguments().size();
                if (ac == arity) byArity.add(n);   // strict: arity was given, so only calls qualify
            }
            if (!byArity.isEmpty()) cands = byArity;
        }
        // then by PARAMETER TYPES when given: two 3-arg constructors are two different call edges
        if (!params.isEmpty()) {
            String want = normParams(params);
            List<Node> byParams = new ArrayList<>();
            for (Node n : cands) {
                try {
                    String got = null;
                    if (n instanceof MethodCallExpr) got = paramSig(((MethodCallExpr) n).resolve());
                    else if (n instanceof ObjectCreationExpr) got = paramSig(((ObjectCreationExpr) n).resolve());
                    if (got != null && got.equals(want)) byParams.add(n);   // strict
                } catch (Throwable ignored) { }
            }
            if (!byParams.isEmpty()) cands = byParams;
        }
        // then by the qualifier the caller gave (package or declaring type)
        if (!qualifier.isEmpty()) {
            List<Node> byQual = new ArrayList<>();
            for (Node n : cands) {
                try {
                    String q = declQualifiedName(n);
                    if (q != null && q.contains(qualifier)) byQual.add(n);
                } catch (Throwable ignored) { }
            }
            if (byQual.isEmpty()) {
                // no reference here belongs to that owner -> search declarations instead of guessing
                return fallback(r, symbol, "no reference to " + qualifier + "." + symbol + " in " + file,
                                arity, params, file, qualifier);
            }
            cands = byQual;
        }

        // if several DISTINCT declarations still match, say so rather than choose arbitrarily
        LinkedHashMap<String, Node> distinct = new LinkedHashMap<>();
        for (Node n : cands) {
            String q;
            try { q = declQualifiedName(n); } catch (Throwable t) { q = null; }
            if (q != null) distinct.putIfAbsent(q, n);
        }
        if (distinct.isEmpty()) {                     // nothing resolvable: keep the old behaviour
            for (Node n : cands) distinct.put("?" + n.getBegin().map(p -> p.line).orElse(0), n);
        }
        if (distinct.size() > 1 && line <= 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"found\":false,\"ambiguous\":true,\"symbol\":\"").append(jesc(symbol))
              .append("\",\"note\":\"'").append(jesc(symbol)).append("' matches ").append(distinct.size())
              .append(" different declarations here. Re-ask naming one exactly — include the parameter list ")
              .append("(e.g. name(int,int,int)) or the fully-qualified type.\",\"candidates\":[");
            boolean first = true;
            for (String q : distinct.keySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("\"").append(jesc(q)).append("\"");
            }
            return sb.append("]}").toString();
        }

        Node best = null; int bestDist = Integer.MAX_VALUE;
        for (Node n : distinct.values()) {
            int bl = n.getBegin().map(p -> p.line).orElse(0);
            int d = line > 0 ? Math.abs(bl - line) : bl;
            if (d < bestDist) { bestDist = d; best = n; }
        }

        try {
            Node def = null; String sig = "";
            if (best instanceof MethodCallExpr) {
                ResolvedMethodDeclaration rd = ((MethodCallExpr) best).resolve();
                sig = safeSig(rd);
                if (rd instanceof JavaParserMethodDeclaration) def = ((JavaParserMethodDeclaration) rd).getWrappedNode();
            } else if (best instanceof ObjectCreationExpr) {
                ResolvedConstructorDeclaration rd = ((ObjectCreationExpr) best).resolve();
                sig = safeSig(rd);
                if (rd instanceof JavaParserConstructorDeclaration) def = ((JavaParserConstructorDeclaration) rd).getWrappedNode();
            } else if (best instanceof ClassOrInterfaceType) {
                ResolvedReferenceTypeDeclaration td = ((ClassOrInterfaceType) best).resolve().asReferenceType().getTypeDeclaration().orElse(null);
                if (td != null) { sig = td.getQualifiedName(); def = astOfType(td); }
            } else if (best instanceof NameExpr || best instanceof FieldAccessExpr) {
                ResolvedValueDeclaration vd = (best instanceof NameExpr)
                        ? ((NameExpr) best).resolve() : ((FieldAccessExpr) best).resolve();
                sig = vd.getName();
                if (vd instanceof JavaParserFieldDeclaration) def = ((JavaParserFieldDeclaration) vd).getWrappedNode();
                // (local vars / params fall through to signature-only — not a useful "walk into" target)
            }
            if (def != null) return defJson(best, sig, def, false);
            // resolved but external (JDK/library) — no source, return the signature
            return "{\"found\":true,\"external\":true,\"symbol\":\"" + jesc(symbol)
                 + "\",\"signature\":\"" + jesc(sig) + "\",\"note\":\"resolves to a JDK/library declaration (no repo source)\"}";
        } catch (Throwable t) {
            return fallback(r, symbol, "could not resolve binding (" + shorten(t.toString()) + ")", arity, params, file, qualifier);
        }
    }

    /** Simple, lowercased, generics-stripped parameter type list — the comparable identity of an
     *  overload, from either side (what the caller wrote vs what the declaration takes). */
    /** package.Owner.member(ParamTypes) — the exact string the caller must pass back as `symbol`. */
    static String fqnOf(CallableDeclaration<?> cd) {
        String pkg = cd.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(pd -> pd.getNameAsString()).orElse("");
        String owner = cd.findAncestor(TypeDeclaration.class)
                .map(td -> ((TypeDeclaration<?>) td).getNameAsString()).orElse("");
        StringBuilder ps = new StringBuilder();
        for (int i = 0; i < cd.getParameters().size(); i++) {
            String t = cd.getParameter(i).getType().asString();
            int lt = t.indexOf('<'); if (lt > 0) t = t.substring(0, lt);
            if (ps.length() > 0) ps.append(',');
            ps.append(t);
        }
        String head = (pkg.isEmpty() ? "" : pkg + ".") + (owner.isEmpty() ? "" : owner);
        boolean ctor = cd instanceof ConstructorDeclaration;
        return (ctor ? head : (head.isEmpty() ? "" : head + ".") + cd.getNameAsString()) + "(" + ps + ")";
    }

    static String fqnOfType(TypeDeclaration<?> td) {
        String pkg = td.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(pd -> pd.getNameAsString()).orElse("");
        return (pkg.isEmpty() ? "" : pkg + ".") + td.getNameAsString();
    }

    static String normParams(String s) {
        StringBuilder sb = new StringBuilder();
        for (String p : s.split(",")) {
            String t = p.trim().toLowerCase();
            int lt = t.indexOf('<');  if (lt > 0) t = t.substring(0, lt);
            int d  = t.lastIndexOf('.'); if (d >= 0) t = t.substring(d + 1);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    static String paramSig(ResolvedMethodLikeDeclaration rd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rd.getNumberOfParams(); i++) {
            String t = rd.getParam(i).describeType().toLowerCase();
            int lt = t.indexOf('<');  if (lt > 0) t = t.substring(0, lt);
            int d  = t.lastIndexOf('.'); if (d >= 0) t = t.substring(d + 1);
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    /** Fully-qualified name of what a reference binds to — the identity that distinguishes an
     *  overload or a same-named class in another package. */
    static String declQualifiedName(Node n) {
        if (n instanceof MethodCallExpr) {
            ResolvedMethodDeclaration rd = ((MethodCallExpr) n).resolve();
            return rd.getQualifiedSignature();
        } else if (n instanceof ObjectCreationExpr) {
            ResolvedConstructorDeclaration rd = ((ObjectCreationExpr) n).resolve();
            return rd.getQualifiedSignature();
        } else if (n instanceof ClassOrInterfaceType) {
            return ((ClassOrInterfaceType) n).resolve().asReferenceType().getQualifiedName();
        }
        return null;
    }

    static String defJson(Node ref, String sig, Node def, boolean ext) {
        String f = def.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                      .map(s -> s.getPath().toString()).orElse("");
        int bl = def.getBegin().map(p -> p.line).orElse(0);
        int el = def.getEnd().map(p -> p.line).orElse(0);
        String src = def.getTokenRange().map(Object::toString).orElse(def.toString());
        if (src.length() > SRC_CAP) src = src.substring(0, SRC_CAP) + "\n/* ...(truncated)... */";
        return "{\"found\":true,\"external\":false,\"signature\":\"" + jesc(sig) + "\",\"defFile\":\""
             + jesc(f) + "\",\"defBeginLine\":" + bl + ",\"defEndLine\":" + el
             + ",\"source\":\"" + jesc(src) + "\"}";
    }

    // grep-style fallback: declarations named `symbol` anywhere in the repo (best-effort)
    /** Resolution by DECLARATION rather than by call site.
     *
     *  The primary path binds a reference (a call) to its declaration. A symbol DECLARED in a file but
     *  never called there — a constructor such as `EntityLinkerProperties(InputStream)` — has no
     *  reference to bind, so that path cannot reach it. Search declarations directly, narrow them the
     *  same way (arity / parameter types / file), and return real source when exactly one survives.
     */
    static String fallback(Repo r, String symbol, String note, int arity, String params, String preferFile, String qualifier) {
        ensureAll(r);
        List<CallableDeclaration<?>> decls = new ArrayList<>();
        List<TypeDeclaration<?>> types = new ArrayList<>();
        if (!symbol.isEmpty()) {
            for (CompilationUnit cu : r.allCus) {
                for (CallableDeclaration<?> cd : cu.findAll(CallableDeclaration.class))
                    if (cd.getNameAsString().equals(symbol)) decls.add(cd);
                for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class))
                    if (td.getNameAsString().equals(symbol)) types.add(td);
            }
        }
        if (arity >= 0 && !decls.isEmpty()) {
            List<CallableDeclaration<?>> f = new ArrayList<>();
            for (CallableDeclaration<?> cd : decls) if (cd.getParameters().size() == arity) f.add(cd);
            if (!f.isEmpty()) decls = f;
        }
        if (!params.isEmpty() && !decls.isEmpty()) {
            String want = normParams(params);
            List<CallableDeclaration<?>> f = new ArrayList<>();
            for (CallableDeclaration<?> cd : decls) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cd.getParameters().size(); i++) {
                    String t = cd.getParameter(i).getType().asString().toLowerCase();
                    int lt = t.indexOf('<');  if (lt > 0) t = t.substring(0, lt);
                    int d  = t.lastIndexOf('.'); if (d >= 0) t = t.substring(d + 1);
                    if (sb.length() > 0) sb.append(',');
                    sb.append(t);
                }
                if (sb.toString().equals(want)) f.add(cd);
            }
            if (!f.isEmpty()) decls = f;
        }
        if (!qualifier.isEmpty() && decls.size() > 1) {    // package.Class from a fully-qualified symbol
            String want = qualifier.substring(qualifier.lastIndexOf('.') + 1);
            List<CallableDeclaration<?>> f = new ArrayList<>();
            for (CallableDeclaration<?> cd : decls) {
                String owner = cd.findAncestor(TypeDeclaration.class).map(td -> ((TypeDeclaration<?>) td).getNameAsString()).orElse("");
                String pkg = cd.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                        .map(pd -> pd.getNameAsString()).orElse("");
                if (owner.equals(want) || (pkg + "." + owner).endsWith(qualifier)) f.add(cd);
            }
            if (!f.isEmpty()) decls = f;
        }
        if (!preferFile.isEmpty() && decls.size() > 1) {   // a sibling in the file being read wins
            List<CallableDeclaration<?>> f = new ArrayList<>();
            for (CallableDeclaration<?> cd : decls) {
                String fp = cd.findCompilationUnit().flatMap(CompilationUnit::getStorage)
                        .map(st -> st.getPath().toString()).orElse("");
                if (fp.endsWith(preferFile)) f.add(cd);
            }
            if (!f.isEmpty()) decls = f;
        }
        if (decls.size() == 1) {
            CallableDeclaration<?> cd = decls.get(0);
            return defJson(cd, oneLine(cd.getDeclarationAsString(false, false, false)), cd, false);
        }
        if (decls.isEmpty() && types.size() == 1) {
            TypeDeclaration<?> td = types.get(0);
            return defJson(td, td.getNameAsString(), td, false);
        }
        if (decls.size() > 1) {
            List<String> sigs = new ArrayList<>();
            for (CallableDeclaration<?> cd : decls) {
                if (sigs.size() >= 12) break;
                sigs.add("\"" + jesc(fqnOf(cd)) + "\"");
            }
            return "{\"found\":false,\"ambiguous\":true,\"symbol\":\"" + jesc(symbol)
                 + "\",\"note\":\"'" + jesc(symbol) + "' is declared " + decls.size() + " times. Copy ONE of "
                 + "the candidates below verbatim into `symbol` — they are already fully qualified.\","
                 + "\"candidates\":[" + String.join(",", sigs) + "]}";
        }
        // nothing matched exactly: offer every fully-qualified option whose simple name is close, so the
        // caller can pick one instead of guessing a package it cannot see.
        List<String> hits = new ArrayList<>();
        for (TypeDeclaration<?> td : types) {
            if (hits.size() >= 20) break;
            hits.add("\"" + jesc(fqnOfType(td)) + "\"");
        }
        if (hits.isEmpty() && !symbol.isEmpty()) {
            String want = symbol.toLowerCase();
            for (CompilationUnit cu : r.allCus) {
                if (hits.size() >= 20) break;
                for (CallableDeclaration<?> cd : cu.findAll(CallableDeclaration.class)) {
                    if (hits.size() >= 20) break;
                    String n = cd.getNameAsString().toLowerCase();
                    if (n.equals(want) || n.contains(want) || want.contains(n)) hits.add("\"" + jesc(fqnOf(cd)) + "\"");
                }
                for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                    if (hits.size() >= 20) break;
                    String n = td.getNameAsString().toLowerCase();
                    if (n.equals(want) || n.contains(want)) hits.add("\"" + jesc(fqnOfType(td)) + "\"");
                }
            }
        }
        return "{\"found\":false,\"note\":\"" + jesc(note)
             + " Candidates below are fully qualified — copy one verbatim into `symbol`.\",\"candidates\":["
             + String.join(",", hits) + "]}";
    }

    // ---- find implementations ---------------------------------------------------------------
    static String implementations(String body) {
        String repoDir = jstr(body, "repoDir"), type = jstr(body, "type"), method = jstr(body, "method");
        Repo r = getRepo(repoDir);
        ensureAll(r);
        List<String> out = new ArrayList<>();
        for (CompilationUnit cu : r.allCus) {
            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cid.isInterface()) continue;
                boolean sub = Stream.concat(cid.getExtendedTypes().stream(), cid.getImplementedTypes().stream())
                        .anyMatch(t -> t.getNameAsString().equals(type));
                if (!sub) continue;
                if (method.isEmpty()) {
                    out.add(implJson(cu, cid.getNameAsString(), cid.getName().getBegin().map(p -> p.line).orElse(0),
                                     cid.getEnd().map(p -> p.line).orElse(0), headerOf(cid)));
                } else {
                    for (MethodDeclaration md : cid.getMethodsByName(method)) {
                        String src = md.getTokenRange().map(Object::toString).orElse(md.toString());
                        if (src.length() > SRC_CAP) src = src.substring(0, SRC_CAP) + "\n/* ...(truncated)... */";
                        out.add(implJson(cu, cid.getNameAsString() + "." + method, md.getBegin().map(p -> p.line).orElse(0),
                                         md.getEnd().map(p -> p.line).orElse(0), src));
                    }
                }
                if (out.size() >= 25) break;
            }
            if (out.size() >= 25) break;
        }
        return "{\"type\":\"" + jesc(type) + "\",\"method\":\"" + jesc(method) + "\",\"count\":" + out.size()
             + ",\"implementations\":[" + String.join(",", out) + "]}";
    }

    static String implJson(CompilationUnit cu, String label, int beginLine, int endLine, String src) {
        String f = cu.getStorage().map(s -> s.getPath().toString()).orElse("");
        return "{\"signature\":\"" + jesc(label) + "\",\"defFile\":\"" + jesc(f)
             + "\",\"defBeginLine\":" + beginLine + ",\"defEndLine\":" + endLine
             + ",\"source\":\"" + jesc(src) + "\"}";
    }

    static synchronized void ensureAll(Repo r) {
        if (r.allCus != null) return;
        r.allCus = new ArrayList<>();
        try (Stream<Path> w = Files.walk(r.dir)) {
            for (Path p : (Iterable<Path>) w.filter(x -> x.toString().endsWith(".java")
                    && !x.toString().contains("/.git/"))::iterator) {
                CompilationUnit cu = parseFile(r, p);
                if (cu != null) r.allCus.add(cu);
            }
        } catch (Exception e) { /* ignore */ }
    }

    // ---- helpers ----------------------------------------------------------------------------
    static boolean nameMatches(String name, String symbol) { return symbol.isEmpty() || name.equals(symbol); }
    static String typeName(Type t) { return t instanceof ClassOrInterfaceType ? ((ClassOrInterfaceType) t).getNameAsString() : t.asString(); }
    static Node astOfType(ResolvedReferenceTypeDeclaration td) {
        if (td instanceof JavaParserClassDeclaration) return ((JavaParserClassDeclaration) td).getWrappedNode();
        if (td instanceof JavaParserInterfaceDeclaration) return ((JavaParserInterfaceDeclaration) td).getWrappedNode();
        if (td instanceof JavaParserEnumDeclaration) return ((JavaParserEnumDeclaration) td).getWrappedNode();
        return null;
    }
    static String headerOf(ClassOrInterfaceDeclaration cid) {
        String s = cid.toString(); int i = s.indexOf('{');
        return i > 0 ? s.substring(0, i + 1) + " ... }" : oneLine(s);
    }
    static String safeSig(ResolvedDeclaration d) { try { return ((ResolvedMethodLikeDeclaration) d).getQualifiedSignature(); } catch (Throwable t) { return d.getName(); } }
    static String oneLine(String s) { return s.replaceAll("\\s+", " ").trim(); }
    static String shorten(String s) { return s.length() > 160 ? s.substring(0, 160) : s; }

    static String jstr(String b, String k) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(b);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\/", "/").replace("\\\\", "\\");
    }
    static int jint(String b, String k) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*(\\d+)").matcher(b);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
    static String err(String msg) { return "{\"found\":false,\"error\":\"" + jesc(msg) + "\"}"; }
    static String jesc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.toString();
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("NAV_PORT", "8099"));
        HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        s.createContext("/health", ex -> send(ex, 200, "{\"ok\":true}"));
        s.createContext("/definition", ex -> {
            try { send(ex, 200, definition(readBody(ex))); }
            catch (Throwable t) { send(ex, 200, err(shorten(String.valueOf(t)))); }
        });
        s.createContext("/implementations", ex -> {
            try { send(ex, 200, implementations(readBody(ex))); }
            catch (Throwable t) { send(ex, 200, err(shorten(String.valueOf(t)))); }
        });
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));   // concurrent methods each nav
        s.start();
        System.out.println("NavServer listening on :" + port);
    }
}
