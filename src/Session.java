import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/*
 Mantiene lo stato di una singola connessione client sul server.

 Un oggetto Session viene creato all'accettazione della connessione TCP e
 allegato alla SelectionKey corrispondente (key.attach(session)).
 Viene poi recuperato da ogni thread del pool che gestisce quella connessione.

 I campi relativi alla partita (mistakes, guessedCategoryNames, ecc.) vengono
 resettati a ogni nuova partita tramite resetForNewGame().
 */
public class Session {
        // Buffer NIO riusabile per la lettura dei dati dal canale (1 KB è sufficiente
        // per le richieste JSON di questa applicazione)
        public final ByteBuffer buffer = ByteBuffer.allocate(1024);

        public String username = null;  // null = giocatore non loggato

        // --- Stato della partita corrente per questo giocatore ---
        public int currentIdGame = -1;          // ID della partita a cui sta partecipando
        public int mistakes = 0;                // errori commessi in questa partita
        public Set<String> guessedCategoryNames = new HashSet<>(); // categorie già trovate
        public boolean isGameOver = false;      // true se ha vinto o perso

        /* Resetta lo stato di gioco quando inizia una nuova partita. */
        public void resetForNewGame(int gameId) {
                this.currentIdGame = gameId;
                this.mistakes = 0;
                this.guessedCategoryNames.clear();
                this.isGameOver = false;
        }
}
