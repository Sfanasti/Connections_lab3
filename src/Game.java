import com.google.gson.annotations.SerializedName;
import java.util.List;

/*
 Rappresenta una singola partita di Connections.
 Contiene l'ID univoco della partita e la lista delle 4 categorie.

 I nomi dei campi JSON ("gameId", "groups") differiscono da quelli Java,
 quindi usiamo @SerializedName per la mappatura Gson.
 */
public class Game {
    @SerializedName("gameId")
    public int id;

    @SerializedName("groups")
    public List<Category> categories; // sempre 4 categorie per partita
}
