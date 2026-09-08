package com.redi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.redi.agent.AgentTool;
import com.redi.agent.ToolRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * inspect_class:用反射查看一个类的结构——字段(类型+名)、public/protected 方法、
 * 构造器、父类/接口;static 基本类型/String 字段可顺带读出值。
 * 严格只读:不调用 setAccessible 写值、不触发修改。
 */
public final class InspectClassTool implements AgentTool {

    private static final int MAX_FIELDS = 50;
    private static final int MAX_METHODS = 80;
    private static final int MAX_VALUES = 10;
    private static final int MAX_CTORS = 10;

    @Override
    public String name() {
        return "inspect_class";
    }

    @Override
    public String description() {
        return "反射查看一个类的结构:声明的字段(类型+名)、public/protected 方法(返回类型+名+参数类型)、构造器、父类与接口;static 的基本类型/String 字段会顺带读出当前值。参数 class_name 填全限定名(如 net.minecraft.world.item.ItemStack)。这是只读操作,不会修改任何东西;找不到类会报错。先用 list_classes 找类名。";
    }

    @Override
    public JsonObject schema() {
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "类的全限定名,如 net.minecraft.world.item.ItemStack");

        JsonObject props = new JsonObject();
        props.add("class_name", name);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("class_name");
        schema.add("required", required);
        return schema;
    }

    @Override
    public String execute(JsonObject args) {
        String name = ToolRegistry.argStr(args, "class_name");
        if (name == null || name.isBlank()) return "缺少参数 class_name。";
        Class<?> c;
        try {
            // initialize=false:只查结构,不触发静态初始化
            c = Class.forName(name.trim(), false, AgentTool.class.getClassLoader());
        } catch (Throwable t) {
            return "找不到类 " + name + "(" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage())
                    + ")。请检查全限定名,或先用 list_classes 查。";
        }
        try {
            return describe(c);
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    /** 组织类的结构描述(纯只读反射)。 */
    private String describe(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        sb.append("类: ").append(c.getName()).append('\n');
        sb.append("种类: ").append(c.isInterface() ? "接口" : c.isEnum() ? "枚举" : c.isRecord() ? "record" : "类")
                .append('\n');
        if (c.getSuperclass() != null) {
            sb.append("父类: ").append(c.getSuperclass().getName()).append('\n');
        }
        Class<?>[] ifaces = c.getInterfaces();
        if (ifaces.length > 0) {
            sb.append("接口: ");
            for (int i = 0; i < ifaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(ifaces[i].getName());
            }
            sb.append('\n');
        }

        Field[] fields = c.getDeclaredFields();
        sb.append("字段(共 ").append(fields.length).append("):\n");
        int n = 0;
        for (Field f : fields) {
            if (n++ >= MAX_FIELDS) {
                sb.append("…(其余略)\n");
                break;
            }
            sb.append("- ").append(f.getType().getSimpleName()).append(' ').append(f.getName());
            if (Modifier.isStatic(f.getModifiers())) sb.append(" [static]");
            sb.append('\n');
        }

        // static 基本类型/String 字段的当前值(只读;私有字段读不到就跳过)
        int values = 0;
        for (Field f : fields) {
            if (values >= MAX_VALUES) break;
            int mod = f.getModifiers();
            if (!Modifier.isStatic(mod)) continue;
            Class<?> ft = f.getType();
            if (!ft.isPrimitive() && ft != String.class) continue;
            try {
                Object v = f.get(null);
                sb.append("常量 ").append(f.getName()).append(" = ").append(v).append('\n');
                values++;
            } catch (Throwable ignored) {
                // 非 public 或类未初始化,跳过
            }
        }

        List<String> methodLines = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue;
            StringBuilder mp = new StringBuilder("- ")
                    .append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) mp.append(", ");
                mp.append(ps[i].getSimpleName());
            }
            mp.append(')');
            if (Modifier.isStatic(mod)) mp.append(" [static]");
            methodLines.add(mp.toString());
        }
        Collections.sort(methodLines);
        sb.append("方法(public/protected,共 ").append(methodLines.size()).append("):\n");
        for (int i = 0; i < methodLines.size() && i < MAX_METHODS; i++) {
            sb.append(methodLines.get(i)).append('\n');
        }
        if (methodLines.size() > MAX_METHODS) sb.append("…(其余略)\n");

        Constructor<?>[] ctors = c.getDeclaredConstructors();
        sb.append("构造器(共 ").append(ctors.length).append("):\n");
        int cn = 0;
        for (Constructor<?> ctor : ctors) {
            if (cn++ >= MAX_CTORS) {
                sb.append("…(其余略)\n");
                break;
            }
            sb.append("- ").append(ctor.getParameterCount()).append("参(");
            Class<?>[] ps = ctor.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(ps[i].getSimpleName());
            }
            sb.append(")\n");
        }
        return ToolRegistry.trunc(sb.toString(), 6000);
    }
}
