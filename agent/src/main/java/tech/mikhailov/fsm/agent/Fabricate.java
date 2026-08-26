package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * THE ONLY THING IN THIS PROGRAM THAT WRITES A STAND-IN, and the reason it is a generator rather
 * than a prompt.
 *
 * <p>An agent told "write an empty stub" writes {@code return true;} on the day the build will not
 * go green otherwise. A critic catches most of those. A GENERATOR CATCHES ALL OF THEM, costs no
 * model call, and cannot be talked round: the model hands back a {@link Declaration} — names, kinds,
 * signatures — and there is no field in it through which a body could arrive.
 *
 * <p>A STUB BODY THAT RETURNS IS A LIE; A STUB BODY THAT THROWS IS A FACT. "No body" is not
 * expressible for a concrete class — javac demands one — so the rule that actually holds is "no body
 * that can be satisfied". Every generated method body throws, naming itself. A test that passes over
 * a throwing stand-in never called it; a test that calls one fails with the fabrication's own name
 * in the surefire output, in the trace, and in front of whoever reads the record.
 *
 * <p>AN EMPTY TYPE IS NOT AUTOMATICALLY AN HONEST ONE, which is the sentence the whole design turns
 * on. {@code WRAuthService} is honest because NOTHING DISPATCHES ON IT — its only use in that tree
 * is a class literal in a {@code @MockBean}. That property is not shared by an annotation a
 * framework reflects over (the aspect that enforced it is gone, so a permission check silently
 * disappears and the module looks BETTER for having lost its security), nor by an enum (the constant
 * set IS the branch set), nor by a supertype (inheritance is a body somebody else wrote). Those
 * three are still allowed — the run would be useless otherwise — but every one of them is counted,
 * named, and caps the module below a clean pass.
 */
final class Fabricate {

    /** What a stand-in is, as data. The model fills this in; only this class writes a file. */
    record Declaration(String fqn, Kind kind, List<String> supertypes, List<Member> members,
                       List<String> enumConstants, String why) {

        enum Kind { INTERFACE, CLASS, ABSTRACT_CLASS, ANNOTATION, ENUM }

        String simple() {
            return fqn.substring(fqn.lastIndexOf('.') + 1);
        }

        String pkg() {
            int at = fqn.lastIndexOf('.');
            return at < 0 ? "" : fqn.substring(0, at);
        }
    }

    /**
     * A member the call sites name.
     *
     * <p>THERE IS NO {@code constant} COMPONENT, and its absence is the point. A draft of this had
     * one, described as "a fabricated value the planner fills in", two paragraphs above prose
     * promising the generator would emit the type's default. When a specification contradicts itself
     * a model takes the reading that gets it a green. There is now no channel through which a chosen
     * value can arrive at all: a {@code static final} field is emitted as its type's default and
     * nothing else — and that default is still counted as a fabricated value, because a constant
     * nobody chose is still a constant somebody's branch will read.
     */
    record Member(String name, String returnType, List<String> parameterTypes, boolean isStatic) {
    }

    /**
     * INHERITANCE IS A BODY WRITTEN BY SOMEBODY ELSE, and it is the subtlest way to fake a green.
     * {@code extends MessageRepositoryBase} gives every call site a real implementation, nothing
     * throws, and a ledger counting members honestly reports zero. Only these four, and each of the
     * three throwables is itself counted as a fabricated decision — {@code extends RuntimeException}
     * is what makes an {@code assertThrows} pass.
     */
    static final Set<String> INHERITABLE = Set.of(
            "java.lang.Object", "java.lang.RuntimeException", "java.lang.Exception",
            "java.lang.Throwable");

    private Fabricate() {
    }

    /**
     * WHAT A PLANNER SAYS, AS LINES — and not as JSON, which is a decision paid for elsewhere.
     *
     * <p>A model asked for JSON produces JSON that is nearly right: a trailing comma, a comment, a
     * key it invented, prose wrapped around the object. Every one of those is a parse failure that
     * costs a turn and teaches nothing, and the model cannot see what it did wrong. A line grammar
     * degrades instead of failing: an unreadable line is dropped, NAMED BACK to the planner, and
     * the readable lines around it still do their work.
     *
     * <pre>
     * interface  ru.nsd.core.wrauthclient.service.WRAuthService
     * class      ru.nsd.a.Boom extends java.lang.RuntimeException
     *   method   go boolean java.lang.String,int
     *   field    CA_FORM java.lang.String
     * enum       ru.nsd.a.State
     *   constant CREATED
     * annotation ru.nsd.a.HasPermission
     * why        the compiler named it at SampleController:29
     * </pre>
     *
     * <p>There is no syntax for a body. That is not enforcement, it is absence: there is nothing to
     * write in.
     */
    record Read(List<Declaration> declarations, List<String> unreadable) {
    }

    static Read read(String plan) {
        List<Declaration> found = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        String fqn = null;
        Declaration.Kind kind = null;
        List<String> supers = new ArrayList<>();
        List<Member> members = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        StringBuilder why = new StringBuilder();

        for (String raw : (plan == null ? "" : plan).split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            String[] word = line.split("[ \\t]+");
            String head = word[0].toLowerCase(Locale.ROOT);
            Declaration.Kind next = switch (head) {
                case "interface" -> Declaration.Kind.INTERFACE;
                case "class" -> Declaration.Kind.CLASS;
                case "abstract" -> Declaration.Kind.ABSTRACT_CLASS;
                case "annotation" -> Declaration.Kind.ANNOTATION;
                case "enum" -> Declaration.Kind.ENUM;
                default -> null;
            };
            if (next != null && word.length >= 2) {
                if (fqn != null) {
                    found.add(new Declaration(fqn, kind, List.copyOf(supers), List.copyOf(members),
                            List.copyOf(constants), why.toString().strip()));
                }
                fqn = word[1];
                kind = next;
                supers = new ArrayList<>();
                members = new ArrayList<>();
                constants = new ArrayList<>();
                why = new StringBuilder();
                for (int i = 2; i + 1 < word.length; i++) {
                    if (word[i].equalsIgnoreCase("extends") || word[i].equalsIgnoreCase("implements")) {
                        supers.addAll(List.of(word[i + 1].split(",")));
                    }
                }
                continue;
            }
            if (fqn == null) {
                // Prose before the first declaration is the model thinking aloud, not an error.
                continue;
            }
            switch (head) {
                case "method" -> {
                    if (word.length < 3) {
                        unreadable.add(line);
                        break;
                    }
                    List<String> parameters = word.length > 3 && !word[3].equals("()")
                            ? List.of(word[3].split(",")) : List.of();
                    members.add(new Member(word[1], word[2], parameters, false));
                }
                case "static" -> {
                    if (word.length < 4 || !word[1].equalsIgnoreCase("method")) {
                        unreadable.add(line);
                        break;
                    }
                    List<String> parameters = word.length > 4 ? List.of(word[4].split(",")) : List.of();
                    members.add(new Member(word[2], word[3], parameters, true));
                }
                // A FIELD IS A MEMBER WITH NO PARAMETER LIST AT ALL, which is what `null` means here
                // and why it is not an empty list: an empty list is a no-argument method.
                case "field" -> {
                    if (word.length < 3) {
                        unreadable.add(line);
                        break;
                    }
                    members.add(new Member(word[1], word[2], null, true));
                }
                case "constant" -> {
                    if (word.length < 2) {
                        unreadable.add(line);
                        break;
                    }
                    constants.add(word[1]);
                }
                case "why" -> why.append(line.substring(3).strip()).append(' ');
                default -> unreadable.add(line);
            }
        }
        if (fqn != null) {
            found.add(new Declaration(fqn, kind, List.copyOf(supers), List.copyOf(members),
                    List.copyOf(constants), why.toString().strip()));
        }
        return new Read(List.copyOf(found), List.copyOf(unreadable));
    }

    /** Why a declaration was refused, for the planner. Never a silent drop. */
    record Refusal(String fqn, String because) {
    }

    record Written(List<Path> files, List<Refusal> refused, List<String> fabricatedValues) {
    }

    /**
     * Write what can honestly be written, refuse the rest, and say which was which.
     *
     * <p>REFUSALS GO BACK TO THE PLANNER IN ITS OWN WORDS. A declaration that is silently dropped is
     * a turn the model believes it spent usefully, and it will spend the next one the same way.
     */
    static Written write(Path checkout, String module, List<Declaration> batch, Baseline baseline) {
        List<Path> files = new ArrayList<>();
        List<Refusal> refused = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Set<String> fabricated = new LinkedHashSet<>();
        batch.forEach(d -> fabricated.add(d.fqn()));

        for (Declaration d : batch) {
            String no = refuse(checkout, d, baseline, fabricated);
            if (no != null) {
                refused.add(new Refusal(d.fqn(), no));
                continue;
            }
            try {
                Path file = pathOf(checkout, module, d);
                Files.createDirectories(file.getParent());
                Files.writeString(file, source(d, values));
                files.add(file);
            } catch (IOException | RuntimeException cannot) {
                refused.add(new Refusal(d.fqn(), "could not be written: " + cannot.getMessage()));
            }
        }
        return new Written(List.copyOf(files), List.copyOf(refused), List.copyOf(values));
    }

    /** The reason this declaration may not be written, or null. */
    private static String refuse(Path checkout, Declaration d, Baseline baseline,
                                 Set<String> fabricated) {
        if (d.fqn() == null || !d.fqn().contains(".") || d.simple().isBlank()) {
            return "a stand-in needs a fully qualified name";
        }
        // A TYPE THE TREE ALREADY DEFINES IS NOT MISSING. Writing over it is how a stand-in stops
        // being a stand-in and starts being a replacement for the code under analysis.
        if (defines(checkout, d.fqn())) {
            return d.fqn() + " is already defined in this tree; a stand-in may not replace it";
        }
        if (baseline != null && baseline.testFiles().stream()
                .anyMatch(p -> p.endsWith("/" + d.simple() + ".java"))) {
            return d.fqn() + " names a test the repository already has; shape 1 writes no tests";
        }
        for (String parent : d.supertypes() == null ? List.<String>of() : d.supertypes()) {
            if (!INHERITABLE.contains(parent) && !fabricated.contains(parent)) {
                return parent + " is a type this tree defines, and inheriting it would give this "
                        + "stand-in behaviour nobody declared. Declare the members the call sites "
                        + "name, or say the module cannot be stubbed honestly.";
            }
        }
        return null;
    }

    private static boolean defines(Path checkout, String fqn) {
        String tail = fqn.replace('.', '/') + ".java";
        try (var walk = Files.walk(checkout)) {
            return walk.anyMatch(p -> p.toString().replace('\\', '/').endsWith("/src/main/java/" + tail)
                    || p.toString().replace('\\', '/').endsWith("/src/test/java/" + tail));
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Where a stand-in goes: the module's own main sources, under its package.
     *
     * <p>MAIN AND NOT TEST, deliberately. A stand-in under {@code src/test/java} is invisible to the
     * main compile, so a type the production code names would still not resolve — and it would sit
     * in the one tree guard A watches, where every write is reverted.
     */
    private static Path pathOf(Path checkout, String module, Declaration d) {
        Path base = module == null || module.isBlank() ? checkout : checkout.resolve(module);
        return base.resolve("src/main/java").resolve(d.pkg().replace('.', '/'))
                .resolve(d.simple() + ".java");
    }

    /** The file, and every fabricated value it contains appended to {@code values}. */
    private static String source(Declaration d, List<String> values) {
        StringBuilder b = new StringBuilder();
        if (!d.pkg().isEmpty()) {
            b.append("package ").append(d.pkg()).append(";\n\n");
        }
        if (d.kind() == Declaration.Kind.ANNOTATION) {
            b.append("import java.lang.annotation.*;\n\n");
        }
        b.append("/**\n * A STAND-IN WRITTEN BY fsm SHAPE 1. IT HAS NO BEHAVIOUR.\n *\n");
        b.append(" * <p>").append(d.why() == null || d.why().isBlank()
                ? "The build named this type and this tree does not define it."
                : d.why().replace("*/", "* /")).append("\n");
        b.append(" *\n * <p>Nothing here was verified. Every method throws; every value is its\n");
        b.append(" * type's default. See .fsm-stubbed for the whole list.\n */\n");

        if (d.kind() == Declaration.Kind.ANNOTATION) {
            // RUNTIME RETENTION, ALWAYS, AND IT IS COUNTED. A generated @interface with no
            // @Retention defaults to CLASS — invisible to reflection — so a fabricated Jackson or
            // JAXB annotation compiles and silently does nothing, and a round-trip test passes
            // because a field was quietly dropped.
            b.append("@Retention(RetentionPolicy.RUNTIME)\n");
            b.append("@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD,\n");
            b.append("         ElementType.PARAMETER, ElementType.CONSTRUCTOR})\n");
            values.add(d.fqn() + " — @Retention(RUNTIME) and @Target, both fabricated");
        }

        b.append("public ").append(keyword(d.kind())).append(' ').append(d.simple());
        List<String> parents = d.supertypes() == null ? List.of() : d.supertypes();
        List<String> extended = parents.stream().filter(p -> !"java.lang.Object".equals(p)).toList();
        if (!extended.isEmpty()) {
            b.append(d.kind() == Declaration.Kind.INTERFACE ? " extends " : " extends ")
                    .append(String.join(", ", extended));
            extended.forEach(p -> values.add(d.fqn() + " extends " + p + " — fabricated inheritance"));
        }
        b.append(" {\n");

        if (d.kind() == Declaration.Kind.ENUM) {
            List<String> constants = d.enumConstants() == null ? List.of() : d.enumConstants();
            // THE CONSTANT SET IS THE BRANCH SET. `switch (t) { case CREATED … }` does not compile
            // without the constant and takes a different path with it; `valueOf` either throws or
            // does not. Every one is a decision nobody made.
            b.append("    ").append(constants.isEmpty() ? ";" : String.join(", ", constants) + ";")
                    .append('\n');
            constants.forEach(c -> values.add(d.fqn() + "." + c + " — fabricated enum constant"));
        }

        for (Member m : d.members() == null ? List.<Member>of() : d.members()) {
            b.append('\n').append(member(d, m, values));
        }
        return b.append("}\n").toString();
    }

    private static String member(Declaration d, Member m, List<String> values) {
        String type = m.returnType() == null || m.returnType().isBlank() ? "void" : m.returnType();
        boolean field = m.parameterTypes() == null;
        if (field) {
            // THE TYPE'S DEFAULT AND NOTHING ELSE. There is no channel for a chosen value, and the
            // default is still a fabrication: a branch reading it takes a path nobody decided.
            values.add(d.fqn() + "." + m.name() + " — fabricated value (" + type + " default)");
            return "    public static final " + type + " " + m.name() + " = " + zero(type) + ";\n";
        }
        String parameters = declared(m.parameterTypes());
        if (d.kind() == Declaration.Kind.INTERFACE || d.kind() == Declaration.Kind.ABSTRACT_CLASS) {
            // NO BODY AT ALL, WHICH IS HONEST BY CONSTRUCTION. An interface method cannot lie.
            String modifier = d.kind() == Declaration.Kind.ABSTRACT_CLASS ? "    public abstract "
                    : "    ";
            return modifier + type + " " + m.name() + "(" + parameters + ");\n";
        }
        return "    public " + (m.isStatic() ? "static " : "") + type + " " + m.name()
                + "(" + parameters + ") {\n"
                + "        throw new UnsupportedOperationException(\n"
                + "                \"fabricated stand-in: fsm shape 1 wrote no body for "
                + d.fqn() + "#" + m.name() + "\");\n    }\n";
    }

    private static String declared(List<String> types) {
        List<String> named = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            named.add(types.get(i) + " a" + i);
        }
        return String.join(", ", named);
    }

    private static String keyword(Declaration.Kind kind) {
        return switch (kind) {
            case INTERFACE -> "interface";
            case ANNOTATION -> "@interface";
            case ENUM -> "enum";
            case ABSTRACT_CLASS -> "abstract class";
            case CLASS -> "class";
        };
    }

    private static String zero(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            case "byte", "short", "int" -> "0";
            case "long" -> "0L";
            case "float" -> "0f";
            case "double" -> "0d";
            default -> "null";
        };
    }
}
