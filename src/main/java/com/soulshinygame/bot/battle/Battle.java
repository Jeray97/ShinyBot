package com.soulshinygame.bot.battle;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Estado de un combate Pokemon entre dos jugadores.
 * Los jugadores eligen su Pokemon de su coleccion.
 * El combate incluye efectividad de tipos y habilidades.
 */
public class Battle {

    public enum State { PENDING, ACTIVE, FINISHED }

    // ── HABILIDADES ───────────────────────────────────────────────────────
    public enum Ability {
        CRITICO     ("Golpe Critico",   "25% prob. de critico (x1.5 dano)"),
        ARMADURA    ("Armadura",        "Dano recibido reducido un 20%"),
        IMPETU      ("Impetu",          "El primer ataque hace el doble de dano"),
        AGUANTE     ("Aguante",         "Con HP < 25% los ataques hacen x1.5"),
        REGENERACION("Regeneracion",    "Recupera 5 HP tras cada ataque propio");

        public final String displayName, description;
        Ability(String d, String desc) { this.displayName = d; this.description = desc; }
    }

    // ── RESULTADO DE ATAQUE ───────────────────────────────────────────────
    public record AttackResult(int damage, double typeMult, boolean isCrit, int healedHp) {}

    // ── ESTADO ────────────────────────────────────────────────────────────
    private static final Random random = new Random();

    public final String challengerName, challengedName;

    // [id, currentHp, atk, def]
    public int[] challengerPokemon;
    public int[] challengedPokemon;
    public String challengerPokemonName, challengedPokemonName;
    public int    challengerMaxHp,        challengedMaxHp;
    public String challengerType1, challengerType2;
    public String challengedType1,  challengedType2;
    public Ability challengerAbility, challengedAbility;

    private boolean challengerFirstDone = false;
    private boolean challengedFirstDone = false;

    public boolean challengerTurn = true;
    public State   state          = State.PENDING;
    public final long createdAt   = System.currentTimeMillis();

    // ── CONSTRUCTOR ───────────────────────────────────────────────────────
    public Battle(String challengerName, String challengedName) {
        this.challengerName = challengerName;
        this.challengedName = challengedName;
    }

    // ── ASIGNACION DE POKEMON ─────────────────────────────────────────────
    public void setChallenger(int id, String name, int hp, int atk, int def,
                              String type1, String type2) {
        challengerPokemon     = new int[]{id, hp, atk, def};
        challengerPokemonName = name;
        challengerMaxHp       = hp;
        challengerType1       = type1 != null ? type1.toLowerCase() : "normal";
        challengerType2       = (type2 != null && !type2.isBlank()) ? type2.toLowerCase() : null;
        challengerAbility     = Ability.values()[random.nextInt(Ability.values().length)];
    }

    public void setChallenged(int id, String name, int hp, int atk, int def,
                              String type1, String type2) {
        challengedPokemon     = new int[]{id, hp, atk, def};
        challengedPokemonName = name;
        challengedMaxHp       = hp;
        challengedType1       = type1 != null ? type1.toLowerCase() : "normal";
        challengedType2       = (type2 != null && !type2.isBlank()) ? type2.toLowerCase() : null;
        challengedAbility     = Ability.values()[random.nextInt(Ability.values().length)];
    }

    public boolean challengerReady() { return challengerPokemon != null; }
    public boolean challengedReady() { return challengedPokemon != null; }

    public void start() { state = State.ACTIVE; }

    // ── ATAQUE ────────────────────────────────────────────────────────────
    public AttackResult attack() {
        int[] atk = challengerTurn ? challengerPokemon : challengedPokemon;
        int[] def = challengerTurn ? challengedPokemon : challengerPokemon;

        Ability atkAbility  = challengerTurn ? challengerAbility : challengedAbility;
        Ability defAbility  = challengerTurn ? challengedAbility : challengerAbility;
        boolean firstAttack = challengerTurn ? !challengerFirstDone : !challengedFirstDone;

        String atkType  = challengerTurn ? challengerType1 : challengedType1;
        String defType1 = challengerTurn ? challengedType1 : challengerType1;
        String defType2 = challengerTurn ? challengedType2 : challengerType2;

        // Dano base
        double factor  = 0.85 + random.nextDouble() * 0.30;
        double baseDmg = Math.max(1, atk[2] * factor - def[3] * 0.5);

        // Efectividad de tipo
        double typeMult = typeEffectiveness(atkType, defType1, defType2);

        // Habilidad atacante
        boolean isCrit  = false;
        double  atkMult = 1.0;
        switch (atkAbility) {
            case IMPETU      -> { if (firstAttack) atkMult = 2.0; }
            case CRITICO     -> { if (random.nextDouble() < 0.25) { isCrit = true; atkMult = 1.5; } }
            case AGUANTE     -> {
                int maxHp = challengerTurn ? challengerMaxHp : challengedMaxHp;
                if (atk[1] < maxHp * 0.25) atkMult = 1.5;
            }
            default -> {}
        }

        // Habilidad defensor (ARMADURA)
        double defMult = (defAbility == Ability.ARMADURA) ? 0.8 : 1.0;

        int damage = (int) Math.max(1, baseDmg * typeMult * atkMult * defMult);
        def[1] = Math.max(0, def[1] - damage);

        // Regeneracion del atacante
        int healedHp = 0;
        if (atkAbility == Ability.REGENERACION) {
            int maxHp = challengerTurn ? challengerMaxHp : challengedMaxHp;
            healedHp  = Math.min(5, maxHp - atk[1]);
            atk[1]    = Math.min(maxHp, atk[1] + healedHp);
        }

        if (challengerTurn) challengerFirstDone = true;
        else challengedFirstDone = true;
        challengerTurn = !challengerTurn;

        return new AttackResult(damage, typeMult, isCrit, healedHp);
    }

    // ── UTILIDADES ────────────────────────────────────────────────────────
    public boolean isOver() {
        return challengerPokemon != null && challengedPokemon != null
               && (challengerPokemon[1] <= 0 || challengedPokemon[1] <= 0);
    }

    public String getWinner() {
        if (challengedPokemon[1] <= 0) return challengerName;
        if (challengerPokemon[1] <= 0) return challengedName;
        return null;
    }

    public String getCurrentTurnName()       { return challengerTurn ? challengerName : challengedName; }
    public String getPokemonName(String p)   { return p.equals(challengerName) ? challengerPokemonName : challengedPokemonName; }
    public int    getCurrentHp(String p)     { return p.equals(challengerName) ? challengerPokemon[1] : challengedPokemon[1]; }
    public int    getMaxHp(String p)         { return p.equals(challengerName) ? challengerMaxHp : challengedMaxHp; }
    public int    getPokemonId(String p)     { return p.equals(challengerName) ? challengerPokemon[0] : challengedPokemon[0]; }
    public Ability getAbility(String p)      { return p.equals(challengerName) ? challengerAbility : challengedAbility; }
    public String  getType1(String p)        { return p.equals(challengerName) ? challengerType1 : challengedType1; }
    public String  getType2(String p)        { return p.equals(challengerName) ? challengerType2 : challengedType2; }

    public String hpBar(String player) {
        int cur    = getCurrentHp(player);
        int max    = getMaxHp(player);
        int filled = (int) Math.max(0, Math.min(10, Math.round((double) cur / max * 10)));
        return "█".repeat(filled) + "░".repeat(10 - filled) + " " + cur + "/" + max;
    }

    // ── TABLA DE TIPOS ────────────────────────────────────────────────────
    public static double typeEffectiveness(String atkType, String defType1, String defType2) {
        if (atkType == null || atkType.isBlank()) return 1.0;
        double m1 = singleEff(atkType, defType1);
        double m2 = (defType2 != null && !defType2.isBlank()) ? singleEff(atkType, defType2) : 1.0;
        return m1 * m2;
    }

    private static double singleEff(String atk, String def) {
        if (def == null || def.isBlank()) return 1.0;
        return TYPE_CHART.getOrDefault(atk + "-" + def, 1.0);
    }

    private static final Map<String, Double> TYPE_CHART = new HashMap<>();
    static {
        String[] x2 = {
            "fire-grass","fire-ice","fire-bug","fire-steel",
            "water-fire","water-ground","water-rock",
            "grass-water","grass-ground","grass-rock",
            "electric-water","electric-flying",
            "ice-grass","ice-ground","ice-flying","ice-dragon",
            "fighting-normal","fighting-ice","fighting-rock","fighting-dark","fighting-steel",
            "poison-grass","poison-fairy",
            "ground-fire","ground-electric","ground-poison","ground-rock","ground-steel",
            "flying-grass","flying-fighting","flying-bug",
            "psychic-fighting","psychic-poison",
            "bug-grass","bug-psychic","bug-dark",
            "rock-fire","rock-ice","rock-flying","rock-bug",
            "ghost-psychic","ghost-ghost",
            "dragon-dragon",
            "dark-psychic","dark-ghost",
            "steel-ice","steel-rock","steel-fairy",
            "fairy-fighting","fairy-dragon","fairy-dark"
        };
        String[] x05 = {
            "fire-water","fire-fire","fire-rock","fire-dragon",
            "water-water","water-grass","water-dragon",
            "grass-fire","grass-grass","grass-poison","grass-flying","grass-bug","grass-steel","grass-dragon",
            "electric-grass","electric-electric","electric-dragon",
            "ice-water","ice-ice","ice-steel","ice-fire",
            "fighting-poison","fighting-flying","fighting-psychic","fighting-bug","fighting-fairy",
            "poison-poison","poison-ground","poison-rock","poison-ghost",
            "ground-grass","ground-bug",
            "flying-electric","flying-rock","flying-steel",
            "psychic-psychic","psychic-steel",
            "bug-fire","bug-fighting","bug-flying","bug-ghost","bug-steel","bug-fairy",
            "rock-fighting","rock-ground","rock-steel",
            "ghost-dark","dragon-steel",
            "dark-fighting","dark-dark","dark-fairy",
            "steel-fire","steel-water","steel-electric","steel-steel",
            "fairy-fire","fairy-poison","fairy-steel",
            "normal-rock","normal-steel"
        };
        String[] x0 = {
            "electric-ground","fighting-ghost","poison-steel",
            "ground-flying","psychic-dark","normal-ghost","ghost-normal","dragon-fairy"
        };
        for (String k : x2)  TYPE_CHART.put(k, 2.0);
        for (String k : x05) TYPE_CHART.put(k, 0.5);
        for (String k : x0)  TYPE_CHART.put(k, 0.0);
    }
}
