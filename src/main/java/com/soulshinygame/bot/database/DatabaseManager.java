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

    public boolean isHealthy() {
        try { userPointsDao.queryForAll(); return true; }
        catch (Exception e) { return false; }
    }

    // ── PUNTOS ────────────────────────────────────────────────────

    public int getPoints(String username) {
        try { UserPoints u = userPointsDao.queryForId(username); return u != null ? u.getPoints() : 0; }
        catch (SQLException e) { log.error("Error getPoints {}", username, e); return 0; }
    }

    public void addPoints(String username, int amount) {
        try {
            UserPoints u = userPointsDao.queryForId(username);
            if (u == null) u = new UserPoints(username, amount);
            else u.setPoints(u.getPoints() + amount);
            userPointsDao.createOrUpdate(u);
        } catch (SQLException e) { log.error("Error addPoints {}", username, e); }
    }

    public void removePoints(String username, int amount) {
        try {
            UserPoints u = userPointsDao.queryForId(username);
            if (u != null) { u.setPoints(Math.max(0, u.getPoints() - amount)); userPointsDao.update(u); }
        } catch (SQLException e) { log.error("Error removePoints {}", username, e); }
    }

    public void setPoints(String username, int points) {
        try {
            UserPoints u = userPointsDao.queryForId(username);
            if (u == null) u = new UserPoints(username, Math.max(0, points));
            else u.setPoints(Math.max(0, points));
            userPointsDao.createOrUpdate(u);
        } catch (SQLException e) { log.error("Error setPoints {}", username, e); }
    }

    public String getTopUsers(int limit) {
        try {
            List<UserPoints> top = userPointsDao.queryBuilder().orderBy("points", false).limit((long) limit).query();
            return top.stream().map(u -> u.getUsername() + "(" + u.getPoints() + "⭐)").collect(Collectors.joining(", "));
        } catch (SQLException e) { log.error("Error getTopUsers", e); return "Error"; }
    }

    public String getAllUsersJson() {
        try {
            List<UserPoints> all = userPointsDao.queryBuilder().orderBy("points", false).query();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < all.size(); i++) {
                UserPoints u = all.get(i);
                sb.append(String.format("{\"username\":\"%s\",\"points\":%d}", u.getUsername(), u.getPoints()));
                if (i < all.size() - 1) sb.append(",");
            }
            return sb.append("]").toString();
        } catch (SQLException e) { log.error("Error getAllUsersJson", e); return "[]"; }
    }

    // ── COLECCIONABLES ────────────────────────────────────────────

    public boolean registerCollectible(String username, String collectionType, String itemId, String itemName) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().eq("collectionType", collectionType).and().eq("itemId", itemId);
            if (qb.countOf() > 0) return false;
            collectibleDao.create(new UserCollectible(username, collectionType, itemId, itemName));
            return true;
        } catch (SQLException e) { log.error("Error registerCollectible", e); return false; }
    }

    public long getCollectionCount(String username, String collectionType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().eq("collectionType", collectionType);
            return qb.countOf();
        } catch (SQLException e) { log.error("Error getCollectionCount", e); return 0; }
    }

    public List<String> getCollection(String username, String collectionType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().eq("collectionType", collectionType);
            return qb.query().stream().map(UserCollectible::getItemName).collect(Collectors.toList());
        } catch (SQLException e) { log.error("Error getCollection", e); return List.of(); }
    }

    /**
     * Devuelve todos los coleccionables de un usuario en un tipo concreto.
     * Usado por ColeccionCommand y BattleSystem.
     */
    public List<UserCollectible> getCollectibles(String username, String collectionType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().eq("collectionType", collectionType);
            return qb.query();
        } catch (SQLException e) { log.error("Error getCollectibles {}/{}", username, collectionType, e); return List.of(); }
    }

    /**
     * Devuelve todos los coleccionables de un usuario EXCEPTO los del tipo indicado.
     * Usado por ColeccionCommand para listar todos los anime (no pokemon).
     */
    public List<UserCollectible> getCollectiblesExcluding(String username, String excludedType) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("username", username).and().ne("collectionType", excludedType);
            return qb.query();
        } catch (SQLException e) { log.error("Error getCollectiblesExcluding {}/{}", username, excludedType, e); return List.of(); }
    }

    public String getCollectionTop(String collectionType, int limit) {
        try {
            QueryBuilder<UserCollectible, Integer> qb = collectibleDao.queryBuilder();
            qb.where().eq("collectionType", collectionType);
            return qb.query().stream()
                    .collect(Collectors.groupingBy(UserCollectible::getUsername, Collectors.counting()))
                    .entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .collect(Collectors.joining(", "));
        } catch (SQLException e) { log.error("Error getCollectionTop", e); return "Error"; }
    }

    public String getAllCollectiblesJson() {
        try {
            List<UserCollectible> all = collectibleDao.queryBuilder().orderBy("username", true).query();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < all.size(); i++) {
                UserCollectible c = all.get(i);
                sb.append(String.format(
                        "{\"username\":\"%s\",\"type\":\"%s\",\"itemId\":\"%s\",\"itemName\":\"%s\"}",
                        c.getUsername(), c.getCollectionType(), c.getItemId(), c.getItemName()));
                if (i < all.size() - 1) sb.append(",");
            }
            return sb.append("]").toString();
        } catch (SQLException e) { log.error("Error getAllCollectiblesJson", e); return "[]"; }
    }
}