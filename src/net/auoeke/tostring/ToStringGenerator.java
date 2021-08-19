package net.auoeke.tostring;

import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.auoeke.reflect.Accessor;
import net.auoeke.reflect.Fields;
import net.auoeke.reflect.Types;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.gudenau.lib.unsafe.Unsafe;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class ToStringGenerator implements Function<Object, String> {
    public static final IdentityHashMap<Class<?>, Function<Object, String>> generators = new IdentityHashMap<>();

    private static final String PROPERTY = "toStringGenerator";

    // This field is used for keeping track of objects that are being stringified in order to avoid cyclic references.
    private static final ThreadLocal<Set<Object>> stringifying = ThreadLocal.withInitial(HashSet::new);

    public static void main(String... args) throws Throwable {
        // The bootstrap class loader does not recognize this class; so we abuse the system properties as a global object store and virtual dispatch by implementing Function.
        System.getProperties().put(PROPERTY, new ToStringGenerator());

        var instrumentation = ByteBuddyAgent.install();
        ObjectNodeTransformer transformer = node -> node.methods.stream().filter(method -> method.name.equals("toString")).findAny().ifPresent(toString -> {
            toString.instructions.clear();
            toString.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(System.class), "getProperties", "()Ljava/util/Properties;", false);
            toString.visitLdcInsn(PROPERTY);
            toString.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(Properties.class), "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
            toString.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(Function.class));
            toString.visitVarInsn(Opcodes.ALOAD, 0);
            toString.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(Function.class), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            toString.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(String.class));
            toString.visitInsn(Opcodes.ARETURN);
        });

        instrumentation.addTransformer(transformer, true);
        instrumentation.retransformClasses(Object.class);
        instrumentation.removeTransformer(transformer);

        //noinspection unused
        System.out.println(new Object() {
            private final int i = 123;
            private final String string = "sample text";
            private final byte b = 0b01110011;
            private final float[] array = {1.5F, 27, 49, 35.1F};
            private final Object dis = this;
            private final Object object = new Object();
            private final Object anonymous = new Object() {
                private final short s = (short) 0xFFFF;
                private final long time = System.nanoTime();
                private final RetentionPolicy policy = RetentionPolicy.RUNTIME;
            };
            private final Object customToString = new Object() {
                @Override
                public String toString() {
                    return "stateless instance of an anonymous Object class";
                }
            };
            private final Object[] objectArray = {this.dis, this.object, this.anonymous, this.customToString};
        });
    }

    @Override
    public final String apply(Object object) {
        var parents = stringifying.get();
        parents.add(object);

        var string = generators.computeIfAbsent(object.getClass(), type -> {
            if (type.isArray()) {
                return array -> Stream.of(Types.box(array)).map(element -> objectString(parents, array, element)).collect(Collectors.joining(", ", defaultString(array) + " [", "]"));
            }

            var fields = Fields.allInstanceFields(type).toArray(Field[]::new);

            return fields.length == 0 ? ToStringGenerator::defaultString : object2 -> {
                var fieldString = new StringBuilder();

                for (var field : fields) {
                    try {
                        fieldString.append(field.getName()).append(": ").append(objectString(parents, object2, Accessor.get(object2, field))).append(System.lineSeparator());
                    } catch (Throwable throwable) {
                        throw Unsafe.throwException(throwable);
                    }
                }

                return Stream.of(fieldString.toString().split(System.lineSeparator()))
                    .map(line -> "    " + line + System.lineSeparator())
                    .collect(Collectors.joining("", defaultString(object2) + " {" + System.lineSeparator(), "}"));
            };

        }).apply(object);

        parents.remove(object);

        return string;
    }

    private static String typeName(Class<?> type) {
        return type.getName().replaceFirst("^java\\.lang\\.", "");
    }

    private static String defaultString(Object object) {
        var type2 = object.getClass();

        return "%s@%x".formatted(type2.isArray() ? "%s[%d]".formatted(typeName(type2.componentType()), Array.getLength(object)) : typeName(type2), object.hashCode());
    }

    private static String objectString(Set<Object> parents, Object original, Object object) {
        return object == original ? "this" : parents.contains(object) ? defaultString(object) : String.valueOf(object);
    }
}
