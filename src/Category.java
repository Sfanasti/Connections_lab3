import com.google.gson.annotations.SerializedName;
import java.util.List;

/*
 Rappresenta una delle 4 categorie di una partita di Connections.
 Ogni categoria ha un tema (name) e una lista di 4 parole (words).

 @SerializedName mappa i campi del JSON ("theme", "words") ai campi Java.
 */
public class Category {
    @SerializedName("theme")
    public String name;     // es. "WET WEATHER"
    public List<String> words; // le 4 parole associate al tema
}
