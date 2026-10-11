package com.keyforge.part2.testsupport;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recording JDBC stubs (dynamic proxies) — no live database. Each {@code executeQuery} snapshots the bound
 * parameters into {@link #executions} and answers one row whose column 1 is TRUE (RETURNING xmax=0 →
 * "inserted"). Statement DDL is recorded in {@link #ddl}. If any bound string equals {@link #FAIL_ON},
 * {@code executeQuery} throws an {@link SQLException} (to exercise per-row failure handling).
 */
public final class RecordingJdbc {

    /** Sentinel for a bound SQL NULL. */
    public static final Object NULL = new Object();
    public static final String FAIL_ON = "__FAIL_THIS_ROW__";

    public final List<Map<Integer, Object>> executions = new ArrayList<>();
    public final List<String> ddl = new ArrayList<>();
    public final List<String> preparedSql = new ArrayList<>();
    /** Bound parameters of every SELECT (lookups / precondition checks), in order. */
    public final List<Map<Integer, Object>> selects = new ArrayList<>();
    /** Answers a SELECT: (sql, bound params) -> column-1 values of the result rows. Default: no rows. */
    public java.util.function.BiFunction<String, Map<Integer, Object>, List<String>> selectAnswer =
            (sql, params) -> List.of();

    public Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "prepareStatement" -> {
                        preparedSql.add((String) args[0]);
                        yield preparedStatement((String) args[0]);
                    }
                    case "createStatement" -> statement();
                    default -> defaultFor(m);
                });
    }

    private PreparedStatement preparedStatement(String sql) {
        Map<Integer, Object> bound = new LinkedHashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "setString", "setBoolean", "setInt" -> bound.put((Integer) args[0], args[1]);
                        case "setNull" -> bound.put((Integer) args[0], NULL);
                        case "executeQuery" -> {
                            if (sql.startsWith("SELECT")) {
                                selects.add(new LinkedHashMap<>(bound));
                                return rows(selectAnswer.apply(sql, bound));
                            }
                            if (bound.containsValue(FAIL_ON)) {
                                throw new SQLException("simulated constraint failure");
                            }
                            executions.add(new LinkedHashMap<>(bound));
                            return insertedResultSet();
                        }
                        default -> { /* close() etc. */ }
                    }
                    return defaultFor(m);
                });
    }

    private Statement statement() {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, m, args) -> {
                    if ("execute".equals(m.getName())) {
                        ddl.add((String) args[0]);
                        return Boolean.FALSE;
                    }
                    return defaultFor(m);
                });
    }

    /** Result set whose column 1 yields the given values, one row each. */
    private ResultSet rows(List<String> values) {
        int[] pos = {-1};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "next" -> ++pos[0] < values.size();
                    case "getString" -> values.get(pos[0]);
                    default -> defaultFor(m);
                });
    }

    private ResultSet insertedResultSet() {
        boolean[] advanced = {false};
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "next" -> {
                        boolean first = !advanced[0];
                        advanced[0] = true;
                        yield first;
                    }
                    case "getBoolean" -> Boolean.TRUE;
                    default -> defaultFor(m);
                });
    }

    private static Object defaultFor(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }
}
