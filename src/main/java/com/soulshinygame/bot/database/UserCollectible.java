package com.soulshinygame.bot.database;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

/**
 * Representa un ítem coleccionable conseguido por un usuario.
 * La clave primaria compuesta es username + collectionType + itemId.
 *
 * Ejemplos:
 *   username="jugador1", collectionType="pokemon",  itemId="25",    itemName="Pikachu"
 *   username="jugador1", collectionType="naruto",   itemId="naruto", itemName="Naruto"
 */
@DatabaseTable(tableName = "user_collectibles")
public class UserCollectible {

    @DatabaseField(generatedId = true)
    private int id;

    @DatabaseField(canBeNull = false, index = true)
    private String username;

    /** Categoría del coleccionable: "pokemon", "naruto", "dragonball"... */
    @DatabaseField(canBeNull = false)
    private String collectionType;

    /** ID único dentro de la colección (número de Pokémon, nombre del personaje...) */
    @DatabaseField(canBeNull = false)
    private String itemId;

    /** Nombre legible para mostrar */
    @DatabaseField
    private String itemName;

    /** Timestamp de cuándo lo consiguió */
    @DatabaseField
    private long obtainedAt;

    public UserCollectible() {}

    public UserCollectible(String username, String collectionType, String itemId, String itemName) {
        this.username       = username;
        this.collectionType = collectionType;
        this.itemId         = itemId;
        this.itemName       = itemName;
        this.obtainedAt     = System.currentTimeMillis();
    }

    public String getUsername()       { return username; }
    public String getCollectionType() { return collectionType; }
    public String getItemId()         { return itemId; }
    public String getItemName()       { return itemName; }
    public long getObtainedAt()       { return obtainedAt; }
}