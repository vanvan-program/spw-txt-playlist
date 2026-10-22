import java.lang.reflect.*;
import java.util.*;
public class InspectHost {
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        Class<?> db = Class.forName("com.xuncorp.voxzen.data.AppDatabase", false, ClassLoader.getSystemClassLoader());
        for (Method getter : db.getDeclaredMethods()) {
            if (!Modifier.isAbstract(getter.getModifiers())) continue;
            Class<?> dao = getter.getReturnType();
            boolean playlist = Arrays.stream(dao.getDeclaredMethods()).anyMatch(m -> m.toGenericString().contains("entity.Playlist"));
            System.out.println("DAO " + getter + " PLAYLIST=" + playlist);
            if (playlist) for (Method m : dao.getDeclaredMethods()) System.out.println(m.toGenericString());
        }
    }
}
