package com.soulshinygame.bot.battle;

import java.util.Random;

/**
 * Representa el estado de un combate Pokémon entre dos jugadores.
 */
public class Battle {

    public enum State { PENDING, ACTIVE, FINISHED }

    // Pokémon predefinidos para los combates (nombre, hp, ataque, defensa)
    private static final int[][] POKEMON_POOL = {
            // { id,  hp, atk, def }
            {  25,  45,  55,  40 }, // Pikachu
            {   1,  45,  49,  49 }, // Bulbasaur
            {   4,  39,  52,  43 }, // Charmander
            {   7,  44,  48,  65 }, // Squirtle
            {   6,  78,  84,  78 }, // Charizard
            {   9,  79,  83,  80 }, // Blastoise
            {   3,  80,  82,  83 }, // Venusaur
            { 150, 106, 110,  90 }, // Mewtwo
            { 149, 91,  134,  95 }, // Dragonite
            {  94,  60,  65,  60 }, // Gengar
            { 131, 130,  85,  80 }, // Lapras
            {  59,  90, 110,  75 }, // Arcanine
            { 130,  95, 125,  79 }, // Gyarados
            {  65,  55,  50,  45 }, // Alakazam
            { 248, 100, 134, 110 }, // Tyranitar
    };

    private static final String[] POKEMON_NAMES = {
            "Pikachu", "Bulbasaur", "Charmander", "Squirtle", "Charizard",
            "Blastoise", "Venusaur", "Mewtwo", "Dragonite", "Gengar",
            "Lapras", "Arcanine", "Gyarados", "Alakazam", "Tyranitar"
    };

    private static final Random random = new Random();

    // Jugadores
    public final String challengerName;
    public final String challengedName;

    // Pokémon de cada jugador [id, hpActual, hpMax, ataque, defensa]
    public int[] challengerPokemon;
    public int[] challengedPokemon;
    public String challengerPokemonName;
    public String challengedPokemonName;

    // Turno: true = le toca al challenger, false = al challenged
    public boolean challengerTurn = true;

    public State state = State.PENDING;

    // Timestamp de creación (para expirar retos sin respuesta)
    public final long createdAt = System.currentTimeMillis();

    public Battle(String challengerName, String challengedName) {
        this.challengerName = challengerName;
        this.challengedName = challengedName;
    }

    /** Asigna Pokémon aleatorios a ambos jugadores y activa la batalla */
    public void start() {
        int idxA = random.nextInt(POKEMON_POOL.length);
        int idxB;
        do { idxB = random.nextInt(POKEMON_POOL.length); } while (idxB == idxA);

        challengerPokemon     = POKEMON_POOL[idxA].clone();
        challengedPokemon     = POKEMON_POOL[idxB].clone();
        challengerPokemonName = POKEMON_NAMES[idxA];
        challengedPokemonName = POKEMON_NAMES[idxB];
        state = State.ACTIVE;
    }

    /** Devuelve el daño causado al atacar. Actualiza el HP del defensor. */
    public int attack() {
        int[] attacker = challengerTurn ? challengerPokemon : challengedPokemon;
        int[] defender = challengerTurn ? challengedPokemon : challengerPokemon;

        // Daño = ataque * factor_aleatorio - defensa * 0.5, mínimo 1
        double factor = 0.85 + random.nextDouble() * 0.30; // 0.85 – 1.15
        int damage = (int) Math.max(1, attacker[2] * factor - defender[3] * 0.5);

        defender[1] = Math.max(0, defender[1] - damage); // restar HP
        challengerTurn = !challengerTurn;                 // cambiar turno

        return damage;
    }

    /** Comprueba si el combate ha terminado */
    public boolean isOver() {
        return challengerPokemon[1] <= 0 || challengedPokemon[1] <= 0;
    }

    /** Devuelve el nombre del ganador, o null si sigue activo */
    public String getWinner() {
        if (challengedPokemon[1] <= 0) return challengerName;
        if (challengerPokemon[1] <= 0) return challengedName;
        return null;
    }

    /** Devuelve el nombre de quien tiene el turno */
    public String getCurrentTurnName() {
        return challengerTurn ? challengerName : challengedName;
    }

    /** Devuelve el nombre del Pokémon de quien tiene el turno */
    public String getCurrentTurnPokemonName() {
        return challengerTurn ? challengerPokemonName : challengedPokemonName;
    }

    /** Devuelve el nombre del Pokémon del jugador dado */
    public String getPokemonName(String player) {
        return player.equals(challengerName) ? challengerPokemonName : challengedPokemonName;
    }

    /** Devuelve el HP actual del Pokémon del jugador dado */
    public int getCurrentHp(String player) {
        return player.equals(challengerName) ? challengerPokemon[1] : challengedPokemon[1];
    }

    /** Devuelve el HP máximo del Pokémon del jugador dado */
    public int getMaxHp(String player) {
        return player.equals(challengerName) ? challengerPokemon[2] : challengedPokemon[2];
    }

    /** Barra de vida visual */
    public String hpBar(String player) {
        int current = getCurrentHp(player);
        int max     = getMaxHp(player);
        int filled  = (int) Math.round((double) current / max * 10);
        return "█".repeat(filled) + "░".repeat(10 - filled) +
                " " + current + "/" + max;
    }

    /** Sprite URL del Pokémon (para posibles usos futuros en overlay) */
    public int getPokemonId(String player) {
        return player.equals(challengerName) ? challengerPokemon[0] : challengedPokemon[0];
    }
}