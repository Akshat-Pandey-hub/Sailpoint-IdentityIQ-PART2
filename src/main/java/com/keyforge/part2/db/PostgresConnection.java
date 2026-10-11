package com.keyforge.part2.db;

import com.keyforge.part2.config.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Opens a JDBC connection to the destination PostgreSQL from {@link AppConfig}. No pooling needed (CLI). */
public final class PostgresConnection {

    private PostgresConnection() {
    }

    public static Connection open(AppConfig config) throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.pgUsername(), config.pgPassword());
    }
}
