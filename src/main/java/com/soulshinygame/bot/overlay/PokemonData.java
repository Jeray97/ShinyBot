package com.soulshinygame.bot.overlay;

public class PokemonData {

    private final int id;
    private final String name;
    private final String type1;
    private final String type2;   // puede ser null
    private final int hp;
    private final int attack;
    private final int defense;
    private final int speed;
    private final String spriteUrl;       // imagen estática oficial
    private final String animatedUrl;     // GIF animado (Gen V)
    private final String cryUrl;          // sonido del pokemon

    public PokemonData(int id, String name, String type1, String type2,
                       int hp, int attack, int defense, int speed,
                       String spriteUrl, String animatedUrl, String cryUrl) {
        this.id = id;
        this.name = name;
        this.type1 = type1;
        this.type2 = type2;
        this.hp = hp;
        this.attack = attack;
        this.defense = defense;
        this.speed = speed;
        this.spriteUrl = spriteUrl;
        this.animatedUrl = animatedUrl;
        this.cryUrl = cryUrl;
    }

    // Serializa a JSON manualmente (sin dependencia extra)
    public String toJson(String triggeredBy) {
        String types = type2 != null
                ? "\"" + type1 + "/" + type2 + "\""
                : "\"" + type1 + "\"";

        return String.format("""
            {
              "type": "pokedex",
              "triggeredBy": "%s",
              "id": %d,
              "name": "%s",
              "types": %s,
              "stats": {
                "hp": %d,
                "attack": %d,
                "defense": %d,
                "speed": %d
              },
              "spriteUrl": "%s",
              "animatedUrl": "%s",
              "cryUrl": "%s"
            }
            """, triggeredBy, id, capitalize(name), types,
                hp, attack, defense, speed,
                spriteUrl, animatedUrl, cryUrl);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    public String getName() { return name; }
    public int getId() { return id; }
}