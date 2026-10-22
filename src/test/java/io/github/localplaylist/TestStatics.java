package io.github.localplaylist;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** 只在独立的宿主集成测试 JVM 中，将宿主数据库单例指向测试数据库。 */
public class TestStatics {
    public static void replace(Field field, Object value) throws Exception {
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), value);
    }
}
