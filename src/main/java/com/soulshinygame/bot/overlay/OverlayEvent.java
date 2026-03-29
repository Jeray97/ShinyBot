package com.soulshinygame.bot.overlay;

/**
 * Representa un evento que se envía al overlay de OBS via WebSocket.
 *
 * Es el modelo genérico que acepta cualquier tipo de media:
 * GIFs, imágenes estáticas, sprites de Pokémon, personajes de anime, etc.
 *
 * El overlay.html sabe mostrar cualquier OverlayEvent sin modificaciones.
 */
public class OverlayEvent {

    // Tipo de evento — por ahora solo "media", extensible en el futuro
    private final String type;

    // Quién lo disparó en el chat
    private final String triggeredBy;

    // Título que aparece en el overlay (ej: "¡Apareció Pikachu!")
    private final String title;

    // URL del GIF, imagen o video a mostrar
    private final String mediaUrl;

    // URL del sonido opcional (puede ser null o vacío)
    private final String soundUrl;

    // Segundos que permanece visible el overlay
    private final int durationSeconds;

    // Color de acento del panel (hex). Ej: "#FF6B35" para naranja, "#7E57C2" para morado
    private final String accentColor;

    private OverlayEvent(Builder builder) {
        this.type            = "media";
        this.triggeredBy     = builder.triggeredBy;
        this.title           = builder.title;
        this.mediaUrl        = builder.mediaUrl;
        this.soundUrl        = builder.soundUrl != null ? builder.soundUrl : "";
        this.durationSeconds = builder.durationSeconds;
        this.accentColor     = builder.accentColor != null ? builder.accentColor : "#e53935";
    }

    /** Serializa a JSON para enviarlo por WebSocket */
    public String toJson() {
        return String.format("""
            {
              "type": "%s",
              "triggeredBy": "%s",
              "title": "%s",
              "mediaUrl": "%s",
              "soundUrl": "%s",
              "durationSeconds": %d,
              "accentColor": "%s"
            }
            """, type, triggeredBy, title, mediaUrl, soundUrl, durationSeconds, accentColor);
    }

    // --- Builder para construcción limpia ---

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String triggeredBy;
        private String title;
        private String mediaUrl;
        private String soundUrl;
        private int durationSeconds = 8;
        private String accentColor;

        public Builder triggeredBy(String user)      { this.triggeredBy = user;      return this; }
        public Builder title(String title)           { this.title = title;           return this; }
        public Builder mediaUrl(String url)          { this.mediaUrl = url;          return this; }
        public Builder soundUrl(String url)          { this.soundUrl = url;          return this; }
        public Builder duration(int seconds)         { this.durationSeconds = seconds; return this; }
        public Builder accentColor(String color)     { this.accentColor = color;     return this; }

        public OverlayEvent build() {
            if (triggeredBy == null || mediaUrl == null || title == null)
                throw new IllegalStateException("triggeredBy, title y mediaUrl son obligatorios");
            return new OverlayEvent(this);
        }
    }
}
