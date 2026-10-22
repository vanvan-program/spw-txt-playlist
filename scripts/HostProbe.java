import java.lang.reflect.*;
import java.util.*;

/** 只加载类型与元数据，不初始化宿主，不访问用户数据库。 */
public class HostProbe {
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        if (args.length == 1 && args[0].startsWith("@")) args = java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0].substring(1))).toArray(String[]::new);
        for (String name : args) {
            try {
                Class<?> c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                System.out.println("\nCLASS " + c.getName());
                for (Field f : c.getDeclaredFields()) System.out.println("FIELD " + f);
                for (Constructor<?> ctor : c.getDeclaredConstructors()) System.out.println("CTOR " + ctor);
                for (Method m : c.getDeclaredMethods()) System.out.println("METHOD " + m.toGenericString() + " RAW " + Arrays.toString(m.getParameterTypes()));
                if (c.isEnum()) for (Object v : c.getEnumConstants()) System.out.println("ENUM " + ((Enum<?>)v).ordinal() + "=" + ((Enum<?>)v).name());
            } catch (Throwable e) {
                System.out.println("FAILED " + name + " " + e);
            }
        }
    }
}
