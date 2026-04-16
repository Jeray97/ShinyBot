package com.soulshinygame.bot.database;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.stmt.QueryBuilder;
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
    private Dao<UserCollectible, Integer> collectibleDao;

    public void init() {
        try {
            connectionSource = new JdbcConnectionSource(DB_URL);
            TableUtils.createTableIfNotExists(connectionSource, UserPoints.class);
            TableUtils.createTableIfNotExists(connectionSource, UserCollectible.class);
            userPointsDao  = DaoManager.createDao(connectionSource, UserPoints.class);
            collectibleDao = DaoManager.createDao(connectionSource, UserCollectible.class);
            log.info("Base de datos inicializada correctamente");
        } catch (SQLException e) {
            log.error("Error iniciando la base de datos", e);
            throw new RuntimeException(e);
        }
    }

    // ── PUNTOS ───────────────────────────────────────────────────

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
            if (user == null) user = new UserPoints(username, amount);
            else user.setPoints(user.getPoints() + amount);
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
                    .orderBy("points", false).limit((long) limit).query();
            return top.stream()
                    .map(u -> u.getUsername() + "(" + u.getPoints() + "⭐)")
                    .collect(Collectors.joining(", "));
        } catch (SQLException e) {
            log.error("Error obteniendo top usuarios", e);
            return "Error al obtener el ranking";
        }
    }

    // ── COLECCIONABLES ───────────────────────────────────────────

    /**
     * Registra un coleccionable para un usuario.
     * Devuelve true si es nuevo, false si ya lo tenía.
     */
    public boolean registerCollectible(String username, String collectionType,
                                       String itemId, String itemName) {
        try {
            // Comprobar si ya lo tiene
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where()
                    .eq("username", username)
                    .and().eq("collectionType", collectionType)
                    .and().eq("itemId", itemId);

            if (qb.countOf() > 0) return false; // ya lo tenía

            collectibleDao.create(new UserCollectible(username, collectionType, itemId, itemName));
            return true;
        } catch (SQLException e) {
            log.error("Error registrando coleccionable", e);
            return false;
        }
    }

    /** Cuántos ítems de una colección tiene un usuario */
    public long getCollectionCount(String username, String collectionType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().eq("collectionType", collectionType);
            return qb.countOf();
        } catch (SQLException e) {
            log.error("Error contando colección", e);
            return 0;
        }
    }

    /** Lista de nombres coleccionados por un usuario en una categoría */
    public List<String> getCollection(String username, String collectionType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().eq("collectionType", collectionType);
            return qb.query().stream()
                    .map(UserCollectible::getItemName)
                    .collect(Collectors.toList());
        } catch (SQLException e) {
            log.error("Error obteniendo colección", e);
            return List.of();
        }
    }

    /** Lista completa de coleccionables (con itemId) de un usuario en una categoría, ordenada por itemId */
    public List<UserCollectible> getCollectibles(String username, String collectionType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().eq("collectionType", collectionType);
            qb.orderBy("itemId", true);
            return qb.query();
        } catch (SQLException e) {
            log.error("Error obteniendo coleccionables de {}", username, e);
            return List.of();
        }
    }

    /** Todos los coleccionables de un usuario que NO sean de una categoría concreta (ej. todo excepto "pokemon") */
    public List<UserCollectible> getCollectiblesExcluding(String username, String excludedType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().ne("collectionType", excludedType);
            qb.orderBy("collectionType", true);
            return qb.query();
        } catch (SQLException e) {
            log.error("Error obteniendo coleccionables de {}", username, e);
            return List.of();
        }
    }

    /** Top usuarios por tamaño de colección */
    public String getCollectionTop(String collectionType, int limit) {
        try {
            // Agrupar por username y contar
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("collectionType", collectionType);
            List<UserCollectible> all = qb.query();

            return all.stream()
                    .collect(Collectors.groupingBy(UserCollectible::getUsername, Collectors.counting()))
                    .entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .collect(Collectors.joining(", "));
        } catch (SQLException e) {
            log.error("Error obteniendo top colección", e);
            return "Error";
        }
    }
}