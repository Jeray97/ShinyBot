package com.soulshinygame.bot.database;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DB_URL = "jdbc:sqlite:bot_data.db";

    private ConnectionSource connectionSource;
    private Dao<UserPoints, String> userPointsDao;

    public void init() {
        try {
            connectionSource = new JdbcConnectionSource(DB_URL);
            TableUtils.createTableIfNotExists(connectionSource, UserPoints.class);
            userPointsDao = DaoManager.createDao(connectionSource, UserPoints.class);
            log.info("Base de datos inicializada correctamente");
        } catch (SQLException e) {
            log.error("Error iniciando la base de datos", e);
            throw new RuntimeException(e);
        }
    }

    public int getPoints(String username) {
        try {
            UserPoints user = userPointsDao.queryForId(username);
            return user != null ? user.getPoints() : 0;
        } catch (SQLException e) {
            log.error("Error obteniendo puntos de {}", username, e);
            return 0;
        }
    }

    public void addPoints(String username, int amount) {
        try {
            UserPoints user = userPointsDao.queryForId(username);
            if (user == null) {
                user = new UserPoints(username, amount);
            } else {
                user.setPoints(user.getPoints() + amount);
            }
            userPointsDao.createOrUpdate(user);
        } catch (SQLException e) {
            log.error("Error añadiendo puntos a {}", username, e);
        }
    }

    public void removePoints(String username, int amount) {
        try {
            UserPoints user = userPointsDao.queryForId(username);
            if (user != null) {
                user.setPoints(Math.max(0, user.getPoints() - amount));
                userPointsDao.update(user);
            }
        } catch (SQLException e) {
            log.error("Error quitando puntos a {}", username, e);
        }
    }

    public String getTopUsers(int limit) {
        try {
            List<UserPoints> top = userPointsDao.queryBuilder()
                    .orderBy("points", false)
                    .limit((long) limit)
                    .query();

            return top.stream()
                    .map(u -> u.getUsername() + "(" + u.getPoints() + "⭐)")
                    .collect(Collectors.joining(", "));
        } catch (SQLException e) {
            log.error("Error obteniendo top usuarios", e);
            return "Error al obtener el ranking";
        }
    }
}
