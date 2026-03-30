package com.soulshinygame.bot.overlay;

public class PokemonData {

    private final int id;
    private final String nameEn;
    private final String nameEs;
    private final String type1;
    private final String type2;
    private final int hp;
    private final int attack;
    private final int defense;
    private final int speed;
    private final String spriteUrl;
    private final String animatedUrl;
    private final String cryUrl;

    public PokemonData(int id, String nameEn, String nameEs, String type1, String type2,
                       int hp, int attack, int defense, int speed,
                       String spriteUrl, String animatedUrl, String cryUrl) {
        this.id          = id;
        this.nameEn      = nameEn;
        this.nameEs      = nameEs;
        this.type1       = type1;
        this.type2       = type2;
        this.hp          = hp;
        this.attack      = attack;
        this.defense     = defense;
        this.speed       = speed;
        this.spriteUrl   = spriteUrl;
        this.animatedUrl = animatedUrl;
        this.cryUrl      = cryUrl;
    }

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
              "stats": { "hp": %d, "attack": %d, "defense": %d, "speed": %d },
              "spriteUrl": "%s",
              "animatedUrl": "%s",
              "cryUrl": "%s"
            }
            """, triggeredBy, id, nameEs, types,
                hp, attack, defense, speed,
                spriteUrl, animatedUrl, cryUrl);
    }

    public int getId()        { return id; }
    public String getNameEn() { return nameEn; }
    public String getNameEs() { return nameEs != null ? nameEs : nameEn; }
}