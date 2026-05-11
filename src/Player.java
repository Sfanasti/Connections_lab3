/*
 Rappresenta un utente registrato al sistema.

 Contiene le credenziali (nome + hash della password), le statistiche aggregate
 di tutte le partite giocate, e lo stato dell'eventuale partita in corso al momento
 dell'ultimo logout (per permettere il ripristino alla riconnessione).

 Questa classe viene serializzata/deserializzata da Gson per la persistenza su file JSON.
 */
public class Player {

    private String name;
    private String psw; // hash SHA-512, mai in chiaro

    // Statistiche globali del giocatore
    private int totalScore = 0;
    private int completed = 0;      // partite totali (vinte + perse + timeout)
    private int wins = 0;
    private int perfect = 0;        // vittorie con 0 errori
    private int losses = 0;         // partite perse (4 errori commessi)
    private int not_finished = 0;   // partite scadute per timeout
    private int currentStreak = 0;  // vittorie consecutive correnti
    private int maxStreak = 0;      // streak massima raggiunta

    // Stato della partita in corso al momento dell'ultimo logout/disconnect.
    // Viene usato per ripristinare la sessione al login successivo.
    private int activeGameId = -1;          // -1 = nessuna partita attiva salvata
    private int activeMistakes = 0;
    private boolean activeGameOver = false;
    private java.util.Set<String> activeGuessedCategories = new java.util.HashSet<>();

    /*
     Istogramma degli errori per le partite terminate:
     -posizione 0-3: vittorie con quel numero di errori commessi
     -posizione 4: sconfitte (4 errori → limite raggiunto)
     -posizione 5: partite non finite (timeout)
     */
    private int[] mistakeHistogram = new int[6];


    public Player(String userName, String psw) {
        this.name = userName;
        this.psw = HashUtils.hashPassword(psw); // la password viene subito hashata
    }

    /* Verifica la password confrontando gli hash. */
    public boolean verifyPassword(String password) {
        return this.psw.equals(HashUtils.hashPassword(password));
    }

    public void setName(String userName) { this.name = userName; }

    /* La password viene sempre hashata prima di essere salvata. */
    public void setPsw(String psw) { this.psw = HashUtils.hashPassword(psw); }

    public String getName() { return this.name; }

    /*
     Aggiorna le statistiche del giocatore al termine di una partita.

     mistakes: numero di errori commessi
     won: true se ha vinto (trovato 3 categorie prima di 4 errori)
     timedOut:true se la partita è scaduta senza vittoria/sconfitta
     scoreGained: punteggio guadagnato in questa partita
     */
    public void recordGame(int mistakes, boolean won, boolean timedOut, int scoreGained) {
        this.completed++;
        this.totalScore += scoreGained;

        if (timedOut) {
            this.not_finished++;
            this.mistakeHistogram[5]++;
            this.currentStreak = 0; // il timeout interrompe la streak

        } else if (won) {
            this.wins++;
            this.currentStreak++;

            if (mistakes == 0) this.perfect++;

            if (mistakes >= 0 && mistakes <= 3) {
                this.mistakeHistogram[mistakes]++;
            }

            if (currentStreak > maxStreak) this.maxStreak = currentStreak;

        } else {
            // Sconfitta (4 errori)
            this.losses++;
            this.currentStreak = 0;
            this.mistakeHistogram[4]++;
        }
    }

    public double winRate() {
        return (completed == 0) ? 0.0 : ((double) wins / completed) * 100;
    }

    public double lossRate() {
        return (completed == 0) ? 0.0 : ((double) losses / completed) * 100;
    }

    public int getTotalScore() { return totalScore; }

    /* Restituisce una stringa formattata con tutte le statistiche (per il comando 'stats'). */
    public String getFullStatsString() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- STATISTICHE PER ").append(name).append(" ---\n");
        sb.append("Puzzles Completed: ").append(completed).append("\n");
        sb.append("Win Rate: ").append(String.format("%.1f", winRate())).append("%\n");
        sb.append("Loss Rate: ").append(String.format("%.1f", lossRate())).append("%\n");
        sb.append("Current Streak: ").append(currentStreak).append("\n");
        sb.append("Max Streak: ").append(maxStreak).append("\n");
        sb.append("Perfect Puzzles: ").append(perfect).append("\n");
        sb.append("Punteggio Totale: ").append(totalScore).append("\n");
        sb.append("Istogramma Errori (0,1,2,3,4,Timeout): [");
        for (int i = 0; i < mistakeHistogram.length; i++) {
            sb.append(mistakeHistogram[i]).append(i == 5 ? "" : ",");
        }
        sb.append("]");
        return sb.toString();
    }

    // --- Getters per lo stato della partita salvata ---
    public int getActiveGameId()                           { return activeGameId; }
    public int getActiveMistakes()                         { return activeMistakes; }
    public boolean isActiveGameOver()                      { return activeGameOver; }
    public java.util.Set<String> getActiveGuessedCategories() { return activeGuessedCategories; }

    /*
     Salva lo stato corrente della partita per permettere il ripristino al prossimo login.
     Viene chiamato al logout o alla disconnessione del giocatore.
     */
    public void saveGameState(int gameId, int mistakes, boolean gameOver,
                              java.util.Set<String> guessed) {
        this.activeGameId = gameId;
        this.activeMistakes = mistakes;
        this.activeGameOver = gameOver;
        this.activeGuessedCategories = new java.util.HashSet<>(guessed); // copia difensiva
    }

    /* Azzera lo stato della partita (chiamato a fine partita o su nuova partita). */
    public void clearGameState() {
        this.activeGameId = -1;
        this.activeMistakes = 0;
        this.activeGameOver = false;
        this.activeGuessedCategories.clear();
    }
}
