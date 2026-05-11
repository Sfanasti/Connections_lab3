import java.util.Set;

/*
 Modello per le richieste JSON inviate dal client al server.

 Gson popola i campi direttamente dal JSON ricevuto; i campi non presenti
 nel JSON rimangono null. I campi sono opzionali a seconda dell'operazione:
 - register/login: username, psw
 - updateCredentials: oldUsername, newUsername, oldPsw, newPsw
 - submitProposal: words (Set di 4 parole)
 - requestGameInfo/Stats: gameId (-1 = partita corrente)
 - requestLeaderboard: topPlayers (top-K) oppure playerName (rank singolo)
 - logout / requestPlayerStats: nessun campo aggiuntivo
 */
public class Request {
    public String operation;    // identifica il tipo di richiesta
    public String username;
    public String psw;
    public String oldUsername;
    public String newUsername;
    public String oldPsw;
    public String newPsw;
    public Set<String> words;   // le 4 parole della proposta
    public Integer gameId;      // null → usa la partita corrente
    public String playerName;   // per richiedere il rank di uno specifico utente
    public Integer topPlayers;  // per richiedere la top-K classifica
}
