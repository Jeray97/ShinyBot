package com.soulshinygame.bot.database;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "user_points")
public class UserPoints {

    @DatabaseField(id = true)
    private String username;

    @DatabaseField
    private int points;

    // Constructor vacío requerido por ORMLite
    public UserPoints() {}

    public UserPoints(String username, int points) {
        this.username = username;
        this.points = points;
    }

    public String getUsername() { return username; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
}
