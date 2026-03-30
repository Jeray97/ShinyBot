package com.soulshinygame.bot.overlay;

/**
 * Representa un personaje de anime leído desde anime_characters.json
 */
public class AnimeData {

    private final int id;
    private final String name;
    private final String series;
    private final String gifUrl;
    private final String rarity;        // comun, raro, epico, legendario
    private final String description;
    private final String accentColor;
    private final String collectionType;
    private final String itemId;

    public AnimeData(int id, String name, String series, String gifUrl,
                     String rarity, String description, String accentColor,
                     String collectionType, String itemId) {
        this.id             = id;
        this.name           = name;
        this.series         = series;
        this.gifUrl         = gifUrl;
        this.rarity         = rarity;
        this.description    = description;
        this.accentColor    = accentColor != null && !accentColor.isEmpty() ? accentColor : "#e53935";
        this.collectionType = collectionType;
        this.itemId         = itemId;
    }

    public String toJson(String triggeredBy) {
        return String.format("""
            {
              "type": "anime",
              "triggeredBy": "%s",
              "id": %d,
              "name": "%s",
              "series": "%s",
              "gifUrl": "%s",
              "rarity": "%s",
              "description": "%s",
              "accentColor": "%s"
            }
            """, triggeredBy, id, name, series, gifUrl, rarity, description, accentColor);
    }

    public int getId()              { return id; }
    public String getName()         { return name; }
    public String getSeries()       { return series; }
    public String getRarity()       { return rarity; }
    public String getCollectionType() { return collectionType; }
    public String getItemId()       { return itemId; }
}