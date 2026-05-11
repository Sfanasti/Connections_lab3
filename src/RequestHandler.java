import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 Gestisce tutte le richieste JSON in arrivo dai client.

 Viene invocato dal thread pool di ServerMain per ogni messaggio ricevuto.
 Ogni operazione viene smistata dal metodo process() in base al campo "operation"
 del JSON. Le risposte vengono scritte direttamente sul SocketChannel del client.
 */
public class RequestHandler {
    private final PlayerManager playerManager;
    private final GameManager gameManager;
    private static final Gson gson = new Gson();

    public RequestHandler(PlayerManager playerManager, GameManager gameManager) {
        this.playerManager = playerManager;
        this.gameManager = gameManager;
    }

    /*
     Punto di ingresso principale: deserializza il messaggio JSON e smista
     l'operazione al ramo corretto dello switch.
     */
    public void process(SelectionKey key, String msg) {
        Session session = (Session) key.attachment();
        SocketChannel cltChannel = (SocketChannel) key.channel();

        try {
            Request request = gson.fromJson(msg, Request.class);
            if (request == null || request.operation == null) return;

            String response;

            switch (request.operation) {

                // ===================== REGISTER =====================
                case "register":
                    boolean ok = playerManager.register(request.username, request.psw);
                    if (ok) {
                        // Dopo la registrazione, facciamo subito il login automatico
                        Player p = playerManager.login(request.username, request.psw);
                        session.username = p.getName();
                        session.currentIdGame = gameManager.getCurrentGame().id;
                        response = buildGameStateResponse("REGISTRAZIONE_AVVENUTA", session,
                                gameManager.getCurrentGame());
                    } else {
                        response = "REGISTRAZIONE_FALLITA";
                    }
                    break;

                // ===================== LOGIN =====================
                case "login":
                    if (playerManager.isOnline(request.username)) {
                        response = "PLAYER_ALREADY_ONLINE";
                    } else {
                        Player player = playerManager.login(request.username, request.psw);
                        if (player != null) {
                            session.username = request.username;
                            Game current = gameManager.getCurrentGame();

                            /*
                             Ripristina lo stato della partita se il giocatore stava
                             giocando la stessa partita ancora in corso
                            */
                            if (player.getActiveGameId() == current.id) {
                                session.currentIdGame = player.getActiveGameId();
                                session.mistakes = player.getActiveMistakes();
                                session.isGameOver = player.isActiveGameOver();
                                session.guessedCategoryNames = new java.util.HashSet<>(
                                        player.getActiveGuessedCategories());
                            } else {
                                // La partita è cambiata mentre era offline: sessione pulita
                                session.currentIdGame = current.id;
                                session.mistakes = 0;
                                session.isGameOver = false;
                                session.guessedCategoryNames.clear();
                                player.clearGameState();
                            }

                            /* Il timer chiama resetForNewGame() su tutte le sessioni attive
                               quando parte una nuova partita, azzerando isGameOver anche per
                               chi aveva già vinto/perso. Controlliamo lo storico per recuperare
                               il vero esito prima di decidere cosa inviare al client.
                             */
                            if (!session.isGameOver) {
                                GameHistoryManager.PlayerResult rec =
                                        ServerMain.gameHistory.getPlayerResult(current.id, session.username);
                                if (rec != null && !rec.timedOut) {
                                    session.isGameOver = true;
                                }
                            }

                            // Se aveva già terminato la partita corrente (vinta o persa)
                            // prima del logout, lo informiamo invece di mandargli la board
                            if (session.isGameOver) {
                                response = buildGameOverLoginResponse(session, current);
                            } else {
                                response = buildGameStateResponse("LOGIN_SUCCESS", session, current);
                            }
                        } else {
                            response = "LOGIN_FAILED";
                        }
                    }
                    break;

                // ===================== LOGOUT =====================
                case "logout":
                    if (session.username != null && playerManager.logout(session.username)) {
                        // Salviamo lo stato della partita in corso così al prossimo login
                        // il giocatore riprende da dove aveva lasciato
                        playerManager.savePlayerGameState(
                                session.username, session.currentIdGame,
                                session.mistakes, session.isGameOver,
                                session.guessedCategoryNames);
                        System.out.println("[SERVER] Logout: " + session.username);
                        response = "LOGOUT_SUCCESS";
                        session.username = null;
                    } else {
                        response = "LOGOUT_FAILED";
                    }
                    break;

                // ===================== UPDATE CREDENTIALS =====================
                case "updateCredentials":
                    // Un utente loggato può aggiornare solo le proprie credenziali
                    if (session.username != null && !session.username.equals(request.oldUsername)) {
                        response = "ERROR_UNAUTHORIZED";
                    } else {
                        response = playerManager.updateCredentials(
                                request.oldUsername, request.newUsername,
                                request.oldPsw, request.newPsw);
                        // Se il nome è cambiato, aggiorniamo anche la sessione corrente
                        if (response.equals("SUCCESS") && session.username != null) {
                            if (request.newUsername != null && !request.newUsername.isEmpty())
                                session.username = request.newUsername;
                        }
                    }
                    break;

                // ===================== SUBMIT PROPOSAL =====================
                case "submitProposal":
                    response = handleSubmitProposal(session, request);
                    break;

                // ===================== LEADERBOARD =====================
                case "requestLeaderboard":
                    String targetName = request.playerName;
                    int k = (request.topPlayers != null) ? request.topPlayers : 0;
                    response = playerManager.getLeaderboard(k, targetName);
                    break;

                // ===================== PLAYER STATS =====================
                case "requestPlayerStats":
                    if (session.username == null) {
                        response = "ERROR_NOT_LOGGED_IN";
                    } else {
                        Player p = playerManager.getPlayer(session.username);
                        if (p == null) response = "PLAYER_NOT_FOUND";
                        else response = p.getFullStatsString();
                    }
                    break;

                // ===================== GAME INFO =====================
                case "requestGameInfo":
                    if (session.username == null) { response = "ERROR_NOT_LOGGED_IN"; break; }

                    int currentId = gameManager.getCurrentGame().id;

                    // Blocca numeri negativi diversi da -1 (convenzione per "partita corrente")
                    if (request.gameId != null && request.gameId < -1) {
                        response = "GAME_NOT_FOUND";
                        break;
                    }

                    int reqId = (request.gameId == null || request.gameId == -1) ? currentId : request.gameId;

                    if (reqId > currentId) { response = "GAME_NOT_FOUND"; break; }

                    Game reqGame = gameManager.getGameById(reqId);
                    if (reqGame == null) { response = "GAME_NOT_FOUND"; break; }

                    // La partita è "in corso" per questo giocatore se è quella attuale
                    // e il giocatore non ha ancora vinto/perso
                    boolean isCurrent = (reqId == currentId) && !gameManager.isPaused();

                    if (isCurrent && !session.isGameOver) {
                        List<String> rem = getRemainingWords(session, reqGame);
                        response = "GAME_INFO|game:" + reqId
                                + "|time:" + gameManager.getTimeRemaining()
                                + "|errors:" + session.mistakes
                                + "|score:" + gameManager.score(session.guessedCategoryNames.size(), session.mistakes)
                                + "|found:" + String.join(";", session.guessedCategoryNames)
                                + "|words:" + String.join(",", rem);
                    } else {
                        // Partita conclusa o storica: mostra soluzione e risultato personale
                        GameHistoryManager.PlayerResult rec =
                                ServerMain.gameHistory.getPlayerResult(reqId, session.username);
                        StringBuilder sb = new StringBuilder("GAME_RESULT|game:").append(reqId);
                        if (rec != null) {
                            sb.append("|found:").append(rec.correctGroups)
                                    .append("|errors:").append(rec.mistakes)
                                    .append("|score:").append(rec.score);
                        } else {
                            sb.append("|found:0|errors:0|score:0");
                        }
                        sb.append("|solution:");
                        for (Category cat : reqGame.categories)
                            sb.append(cat.name).append("=").append(String.join(",", cat.words)).append(";");
                        response = sb.toString();
                    }
                    break;

                // ===================== GAME STATS =====================
                case "requestGameStats":
                    if (session.username == null) {
                        response = "ERROR_NOT_LOGGED_IN";
                        break;
                    }
                    int curId = gameManager.getCurrentGame().id;

                    if (request.gameId != null && request.gameId < -1) {
                        response = "GAME_NOT_FOUND";
                        break;
                    }

                    int targetGameId = (request.gameId == null || request.gameId == -1) ? curId : request.gameId;

                    if (targetGameId > curId || gameManager.getGameById(targetGameId) == null) {
                        response = "GAME_NOT_FOUND";
                        break;
                    }

                    boolean isCurrentGame = (targetGameId == curId) && !gameManager.isPaused();

                    if (isCurrentGame) {
                        // Partita in corso: usa le statistiche in memoria (aggiornate in tempo reale)
                        GameManager.GameStats stats = gameManager.getOrCreateStats(targetGameId);
                        int stillPlaying = stats.participants - stats.finished;
                        response = "GAME_STATS_LIVE"
                                + "|game:" + targetGameId
                                + "|time:" + gameManager.getTimeRemaining()
                                + "|playing:" + stillPlaying
                                + "|finished:" + stats.finished
                                + "|winners:" + stats.winners;
                    } else {
                        // Partita passata: leggi dallo storico persistente
                        List<GameHistoryManager.PlayerResult> results =
                                ServerMain.gameHistory.getResults(targetGameId);
                        int participants = results.size();
                        int finished = (int) results.stream().filter(r -> !r.timedOut).count();
                        int winners   = (int) results.stream().filter(r -> r.won).count();
                        double avg    = participants > 0
                                ? results.stream().mapToInt(r -> r.score).average().orElse(0.0)
                                : 0.0;
                        response = "GAME_STATS_FINAL"
                                + "|game:" + targetGameId
                                + "|participants:" + participants
                                + "|finished:" + finished
                                + "|winners:" + winners
                                + "|avgScore:" + String.format("%.1f", avg);
                    }
                    break;

                default:
                    response = "UNKNOWN_OPERATION";
            }

            sendResponse(cltChannel, response);

        } catch (Exception e) {
            System.err.println("[HANDLER ERROR]: " + e.getMessage());
        }
    }

    // ===================== SUBMIT PROPOSAL LOGIC =====================

    /*
     Gestisce la logica di una proposta di gruppo inviata dal giocatore.

     L'intero metodo è sincronizzato sulla sessione del giocatore per evitare
     che il thread del timer (che chiama resetForNewGame) e il thread del pool
     modifichino la sessione contemporaneamente.

     Controlla in ordine:
     1. Se il server è in pausa
     2. Se la partita del giocatore è scaduta (deve passare alla nuova)
     3. Se il giocatore ha già terminato questa partita
     4. Se la proposta è malformata (parole non valide o già usate)
     5. Se la proposta è corretta o sbagliata
     */
    private String handleSubmitProposal(Session session, Request request) {

        synchronized (session) {
            // 1. Pausa tra partite
            if (gameManager.isPaused()) {
                return "WAIT|Il gioco è in pausa per il cambio partita. Attendi qualche secondo.";
            }

            // 2. La partita del giocatore è scaduta mentre era online
            if (session.currentIdGame != gameManager.getCurrentGame().id) {
                int partialScore = gameManager.score(
                        session.guessedCategoryNames.size(), session.mistakes);

                // Registriamo il timeout solo se non era già stato fatto dal timer
                // (il timer potrebbe averlo già scritto su gameHistory)
                GameHistoryManager.PlayerResult existing =
                        ServerMain.gameHistory.getPlayerResult(session.currentIdGame, session.username);
                if (existing == null) {
                    playerManager.updatePlayerStats(
                            session.username, session.mistakes, false, true, partialScore);
                    gameManager.recordResult(session.currentIdGame, false, partialScore);
                    playerManager.clearAndSave(session.username);
                    ServerMain.gameHistory.recordResult(
                            session.currentIdGame, session.username,
                            session.guessedCategoryNames.size(), session.mistakes,
                            partialScore, false, true);
                }

                // Passa alla nuova partita e informa il client
                int oldGameId = session.currentIdGame;
                Game newGame = gameManager.getCurrentGame();
                session.resetForNewGame(newGame.id);
                List<String> newWords = newGame.categories.stream()
                        .flatMap(c -> c.words.stream())
                        .collect(java.util.stream.Collectors.toList());
                java.util.Collections.shuffle(newWords);

                return "TIMEOUT|Tempo scaduto per partita #" + oldGameId
                        + "! Punteggio accumulato: " + partialScore
                        + "|NUOVA_PARTITA|game:" + newGame.id
                        + "|time:" + gameManager.getTimeRemaining()
                        + "|words:" + String.join(",", newWords);
            }

            // 3. Partita già terminata per questo giocatore
            if (session.isGameOver) {
                return "MALFORMED|Hai già terminato questa partita. Attendi la prossima.";
            }

            // 4. Controllo numero parole
            if (request.words == null || request.words.size() != 4) {
                return "MALFORMED|Devi inviare esattamente 4 parole.";
            }

            Game currentGame = gameManager.getCurrentGame();

            // ====== FASE 1: CONTROLLI MALFORMED ======
            // Le proposte malformate non costano errori né influenzano il punteggio.

            // Raccoglie tutte le parole valide della partita per verificare quelle inviate
            Set<String> allValidWords = new HashSet<>();
            for (Category c : currentGame.categories) {
                for (String w : c.words) allValidWords.add(w.toUpperCase());
            }

            for (String w : request.words) {
                if (!allValidWords.contains(w.toUpperCase())) {
                    return "MALFORMED|La parola '" + w + "' non fa parte di questa partita.";
                }
            }

            // Verifica che non si stiano riutilizzando parole di categorie già trovate
            for (Category c : currentGame.categories) {
                if (session.guessedCategoryNames.contains(c.name)) {
                    for (String w : request.words) {
                        for (String wordInCat : c.words) {
                            if (w.equalsIgnoreCase(wordInCat)) {
                                return "MALFORMED|Stai usando parole già assegnate correttamente a un gruppo.";
                            }
                        }
                    }
                }
            }

            // ====== FASE 2: VALUTAZIONE DELLA PROPOSTA ======

            // Registriamo la partecipazione solo alla prima proposta valida
            // (guessedCategoryNames vuoto e mistakes == 0 → primo tentativo assoluto)
            if (session.guessedCategoryNames.isEmpty() && session.mistakes == 0) {
                gameManager.recordParticipation(currentGame.id);
            }

            Category matchingCat = gameManager.getMatchingCategory(request.words);

            if (matchingCat != null) {
                // --- PROPOSTA CORRETTA ---
                session.guessedCategoryNames.add(matchingCat.name);

                // Vittoria automatica alla terza categoria: la quarta è implicita per esclusione
                if (session.guessedCategoryNames.size() == currentGame.categories.size() - 1) {
                    Category lastCat = currentGame.categories.stream()
                            .filter(c -> !session.guessedCategoryNames.contains(c.name))
                            .findFirst().orElse(null);

                    if (lastCat != null) session.guessedCategoryNames.add(lastCat.name);

                    session.isGameOver = true;
                    int finalScore = gameManager.score(currentGame.categories.size(), session.mistakes);
                    playerManager.updatePlayerStats(
                            session.username, session.mistakes, true, false, finalScore);
                    gameManager.recordResult(currentGame.id, true, finalScore);
                    playerManager.clearAndSave(session.username);
                    // Notifica tutti i client via multicast che questo giocatore ha vinto
                    ServerMain.mcastGameEnd(
                            session.username + " ha vinto la partita #" + currentGame.id);
                    ServerMain.gameHistory.recordResult(currentGame.id, session.username,
                            session.guessedCategoryNames.size(), session.mistakes, finalScore, true, false);
                    String autoCategory = (lastCat != null) ? lastCat.name : "";
                    return "WON_GAME|" + matchingCat.name + "|" + autoCategory + "|Punteggio: " + finalScore;
                }

                // Categoria corretta ma partita non ancora vinta: salviamo lo stato
                playerManager.savePlayerGameState(
                        session.username, session.currentIdGame,
                        session.mistakes, session.isGameOver,
                        session.guessedCategoryNames);
                return "CORRECT_GROUP|" + matchingCat.name + "|"
                        + String.join(",", getRemainingWords(session, currentGame));

            } else {
                // --- PROPOSTA SBAGLIATA ---
                session.mistakes++;

                if (session.mistakes >= 4) {
                    // Limite di 4 errori raggiunto: il giocatore perde
                    session.isGameOver = true;
                    int finalScore = gameManager.score(session.guessedCategoryNames.size(), 4);
                    playerManager.updatePlayerStats(session.username, 4, false, false, finalScore);
                    gameManager.recordResult(currentGame.id, false, finalScore);
                    playerManager.clearAndSave(session.username);
                    ServerMain.mcastGameEnd(
                            session.username + " ha perso la partita #" + currentGame.id);
                    ServerMain.gameHistory.recordResult(currentGame.id, session.username,
                            session.guessedCategoryNames.size(), 4, finalScore, false, false);
                    return "LOST_GAME|Hai esaurito i tentativi. Punteggio: " + finalScore;
                }

                // Errore ma non ancora al limite: salviamo lo stato
                playerManager.savePlayerGameState(
                        session.username, session.currentIdGame,
                        session.mistakes, session.isGameOver,
                        session.guessedCategoryNames);
                return "WRONG_PROPOSAL|Sbagliato! Errori commessi: " + session.mistakes + "/4";
            }
        }
    }

    // ===================== METODI DI SUPPORTO =====================

    /*
     Costruisce la risposta da inviare al login quando il giocatore aveva già
     terminato la partita corrente (vinto o perso) prima di fare logout.
     Invece di mostrargli la board, lo informa del suo esito e gli chiede di aspettare.
     */
    private String buildGameOverLoginResponse(Session session, Game current) {
        GameHistoryManager.PlayerResult rec =
                ServerMain.gameHistory.getPlayerResult(current.id, session.username);

        StringBuilder sb = new StringBuilder("LOGIN_GAME_OVER");
        sb.append("|game:").append(current.id);
        sb.append("|time:").append(gameManager.getTimeRemaining());

        if (rec != null) {
            // Dati precisi dallo storico
            sb.append("|outcome:").append(rec.won ? "won" : "lost");
            sb.append("|found:").append(rec.correctGroups);
            sb.append("|errors:").append(rec.mistakes);
            sb.append("|score:").append(rec.score);
        } else {
            // Fallback: usa i dati della sessione (ha vinto se guessed == 4 categorie)
            boolean won = session.guessedCategoryNames.size() == current.categories.size();
            int score = gameManager.score(session.guessedCategoryNames.size(), session.mistakes);
            sb.append("|outcome:").append(won ? "won" : "lost");
            sb.append("|found:").append(session.guessedCategoryNames.size());
            sb.append("|errors:").append(session.mistakes);
            sb.append("|score:").append(score);
        }

        // Manda anche la soluzione completa
        sb.append("|solution:");
        for (Category cat : current.categories)
            sb.append(cat.name).append("=").append(String.join(",", cat.words)).append(";");

        return sb.toString();
    }

    /*
     Restituisce le parole delle categorie non ancora trovate dal giocatore,
     in ordine casuale (shuffle per non rivelare i raggruppamenti).
     */
    private List<String> getRemainingWords(Session session, Game game) {
        List<String> words = game.categories.stream()
                .filter(c -> !session.guessedCategoryNames.contains(c.name))
                .flatMap(c -> c.words.stream())
                .collect(java.util.stream.Collectors.toList());
        java.util.Collections.shuffle(words);
        return words;
    }

    /* Costruisce la risposta standard di stato partita (usata al login e alla registrazione). */
    private String buildGameStateResponse(String prefix, Session session, Game current) {
        int currentScore = gameManager.score(
                session.guessedCategoryNames.size(), session.mistakes);

        return prefix
                + "|game:" + current.id
                + "|time:" + gameManager.getTimeRemaining()
                + "|errors:" + session.mistakes
                + "|score:" + currentScore
                + "|found:" + String.join(";", session.guessedCategoryNames)
                + "|words:" + String.join(",", getRemainingWords(session, current));
    }

    /* Scrive la risposta sul canale TCP del client aggiungendo '\n' come terminatore. */
    private void sendResponse(SocketChannel channel, String response) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap((response + "\n").getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
        } catch (IOException e) {
            System.err.println("[SEND ERROR]: " + e.getMessage());
        }
    }
}
