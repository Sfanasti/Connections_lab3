import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;

/*
 Punto di ingresso del server per il gioco Connections.

 Architettura generale:
 - Un selettore NIO (non-blocking) accetta le connessioni e rileva quando arrivano dati da leggere.
 - Le letture vengono delegate a un thread pool (ExecutorService) per non
   bloccare il ciclo principale del selettore.
 - Un ScheduledExecutorService separato gestisce il timer delle partite:
   (fine partita --> pausa --> nuova partita).
 - Le notifiche asincrone (fine partita, classifica) vengono inviate via UDP
   multicast a tutti i client connessi.

 I parametri di configurazione vengono letti da serverConfig.properties.
 */
public class ServerMain {
    private static final String configFile = "serverConfig.properties";
    private static int tcpPort;
    private static int game_time_second;  // durata di ogni partita in secondi
    private static int thread_pool_size;
    private static int pause_time;        // pausa tra una partita e l'altra (secondi)
    private static String mcast_address;
    private static int mcast_port;

    private static ScheduledExecutorService gameTimer; // timer per il ciclo delle partite
    private static ExecutorService pool;               // thread pool per le richieste TCP
    private static PlayerManager playerManager;
    private static GameManager gameManager;
    private static RequestHandler requestHandler;
    private static Selector mainSelector;
    private static ServerSocketChannel serverChannel;
    public static GameHistoryManager gameHistory; // public per accesso da RequestHandler

    // Flag letto dal ciclo principale; volatile per visibilità tra thread
    private static volatile boolean running = true;

    // Set di tutte le sessioni attive (connessioni TCP aperte)
    private static final Set<Session> activeSessions =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void main(String[] args) {
        try {
            readConfig();
            pool = Executors.newFixedThreadPool(thread_pool_size);
            startGlobalTimer();
        } catch (IOException e) {
            System.err.println("[ERRORE CONFIG] " + e.getMessage());
            return;
        }

        // Shutdown hook: viene eseguito quando l'utente preme CTRL+C o il processo viene terminato
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[HOOK] Shutdown intercettato (CTRL+C)");
            shutdown();
        }));

        try {
            mainSelector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(tcpPort));
            serverChannel.configureBlocking(false); // NIO non-blocking
            serverChannel.register(mainSelector, SelectionKey.OP_ACCEPT);

            System.out.println("[SERVER] In ascolto sulla porta " + tcpPort + "...");

            while (running) {
                // select() blocca finché non ci sono chiavi pronte; ritorna 0 se il timeout scade
                if (mainSelector.select() == 0) continue;

                Set<SelectionKey> keys = mainSelector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove(); // obbligatorio: il selettore non rimuove le chiavi da solo

                    if (key.isAcceptable()) {
                        handleAccept(mainSelector, key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[ERRORE SERVER] " + e.getMessage());
        }
    }

    /*
     Avvia il timer globale delle partite.

     Il ciclo funziona così:
     1. startGame() segna l'inizio della partita corrente.
     2. Dopo game_time_second secondi, G_cycle viene eseguito:
        - Mette il server in pausa (startTransition).
        - Aspetta pause_time secondi (lambda interna).
        - Registra i timeout, aggiorna la classifica, avanza alla partita successiva.
        - Si ri-schedula per il prossimo ciclo.

     Si usa un ScheduledExecutorService con un singolo thread per evitare che due
     cicli possano eseguirsi contemporaneamente.
     */
    private static void startGlobalTimer() {
        gameTimer = Executors.newSingleThreadScheduledExecutor();

        Runnable G_cycle = new Runnable() {
            @Override
            public void run() {
                try {
                    gameManager.startTransition(); // mette in pausa il server
                    sendMCastMsg("[TIMER] Partita terminata. Inizio pausa di " + pause_time + "s...");

                    // Dopo la pausa, eseguiamo la transizione vera e propria
                    gameTimer.schedule(() -> {
                        int expiredGameId = gameManager.getCurrentGame().id;

                        // 1. Registra il timeout per i giocatori ONLINE che non hanno terminato
                        for (Session s : getActiveSessions()) {
                            if (s.username != null && !s.isGameOver
                                    && s.currentIdGame == expiredGameId) {
                                int partial = gameManager.score(
                                        s.guessedCategoryNames.size(), s.mistakes);
                                playerManager.updatePlayerStats(
                                        s.username, s.mistakes, false, true, partial);
                                playerManager.clearAndSave(s.username);
                                gameHistory.recordResult(
                                        expiredGameId, s.username,
                                        s.guessedCategoryNames.size(), s.mistakes,
                                        partial, false, true);
                            }
                        }

                        // 2. Registra il timeout per i giocatori OFFLINE con partita ancora aperta
                        playerManager.recordTimeoutForOfflinePlayers(
                                expiredGameId, gameManager, gameHistory);

                        // 3. Invia la classifica finale via multicast
                        String leaderboard = playerManager.getLeaderboard(0, null);
                        sendMCastMsg("[FINE PARTITA] Classifica finale dopo partita #"
                                + expiredGameId + "\n" + leaderboard);

                        // 4. Segna la partita come completamente terminata.
                        //    Fatto QUI, dopo tutti i timeout e prima di next():
                        //    solo ora il ciclo della partita è davvero chiuso.
                        gameHistory.markGameCompleted(expiredGameId);

                        // 5. Avanza alla partita successiva
                        gameManager.next();
                        int newGameId = gameManager.getCurrentGame().id;

                        // 6. Resetta le sessioni DOPO next() per evitare bug di wrap-around:
                        //    se resettassimo prima, i giocatori vedrebbero il vecchio ID
                        for (Session s : getActiveSessions()) {
                            if (s.username != null) {
                                s.resetForNewGame(newGameId);
                            }
                        }

                        // 7. Notifica tutti i client della nuova partita
                        sendMCastMsg("[TIMER] Nuova partita #" + newGameId
                                + " avviata! Digita 'gameinfo' per vedere lo stato della partita.");

                        // 8. Ripianifica il prossimo ciclo (this = questo Runnable anonimo)
                        gameTimer.schedule(this, game_time_second, TimeUnit.SECONDS);

                    }, pause_time, TimeUnit.SECONDS);

                } catch (Exception e) {
                    System.err.println("[TIMER] Errore: " + e.getMessage());
                }
            }
        };

        gameManager.startGame();
        sendMCastMsg("[TIMER] Server avviato: Prima partita #"
                + gameManager.getCurrentGame().id + " iniziata!");

        // Prima esecuzione del ciclo dopo game_time_second secondi
        gameTimer.schedule(G_cycle, game_time_second, TimeUnit.SECONDS);
    }

    /* Legge i parametri da serverConfig.properties e inizializza i manager principali. */
    private static void readConfig() throws IOException {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            prop.load(fis);
            tcpPort           = Integer.parseInt(prop.getProperty("tcpPort",           "6969"));
            thread_pool_size  = Integer.parseInt(prop.getProperty("thread_pool_size",  "20"));
            game_time_second  = Integer.parseInt(prop.getProperty("game_time_second",  "360"));
            pause_time        = Integer.parseInt(prop.getProperty("pause_time",        "20"));
            mcast_address     = prop.getProperty("multicastAddress", "239.0.0.1");
            mcast_port        = Integer.parseInt(prop.getProperty("multicastPort",     "6789"));
            String gamesFile  = prop.getProperty("gamesFile", "Connections_Data.json");

            gameHistory    = new GameHistoryManager();
            gameManager    = new GameManager(gamesFile, game_time_second, gameHistory);
            playerManager  = new PlayerManager();
            requestHandler = new RequestHandler(playerManager, gameManager);

        } catch (FileNotFoundException e) {
            throw new FileNotFoundException("File '" + configFile + "' non trovato nella root.");
        } catch (NumberFormatException e) {
            throw new IOException("Errore nei valori numerici del file config.");
        }
    }

    /*
     Accetta una nuova connessione TCP e crea una Session associata alla SelectionKey.
     La Session viene allegata alla chiave (key.attach) così ogni thread del pool
     può recuperarla senza ricerche aggiuntive.
     */
    private static void handleAccept(Selector selector, SelectionKey key) {
        ServerSocketChannel srvChannel = (ServerSocketChannel) key.channel();
        try {
            SocketChannel clientChannel = srvChannel.accept();
            if (clientChannel != null) {
                clientChannel.configureBlocking(false);
                Session s = new Session();
                clientChannel.register(selector, SelectionKey.OP_READ, s);
                activeSessions.add(s);
                System.out.println("[SERVER] Connesso: " + clientChannel.getRemoteAddress());
            }
        } catch (IOException e) {
            System.err.println("[ERRORE] Accept fallita: " + e.getMessage());
        }
    }

    /*
     Gestisce la lettura di dati da un client.

     OP_READ viene rimosso subito per evitare che il selettore ri-selezioni la
     chiave mentre il thread del pool la sta ancora processando. Viene re-aggiunto
     al termine dell'elaborazione.
     */
    private static void handleRead(SelectionKey key) {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);

        pool.execute(() -> {
            try {
                SocketChannel clientChannel = (SocketChannel) key.channel();
                Session session = (Session) key.attachment();
                ByteBuffer buffer = session.buffer;

                buffer.clear();
                int bytesRead = clientChannel.read(buffer);

                if (bytesRead == -1) {
                    // Disconnessione pulita (EOF): salviamo lo stato e facciamo logout
                    if (session.username != null) {
                        playerManager.savePlayerGameState(
                                session.username, session.currentIdGame,
                                session.mistakes, session.isGameOver,
                                session.guessedCategoryNames
                        );
                        playerManager.logout(session.username);
                        System.out.println("[SERVER] Logout: " + session.username);
                    }
                    activeSessions.remove(session);
                    clientChannel.close();
                    key.cancel();
                    return;
                }

                buffer.flip(); // prepara il buffer per la lettura
                String message = StandardCharsets.UTF_8.decode(buffer).toString().trim();

                requestHandler.process(key, message);

                // Re-abilita la lettura per ricevere il prossimo messaggio del client
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                key.selector().wakeup();

            } catch (IOException e) {
                // Disconnessione brusca (es. crash del client): puliamo la sessione
                // come facciamo nel caso bytesRead == -1, altrimenti il giocatore
                // resta "online" per sempre e non può più fare login.
                Session sess = (Session) key.attachment();
                if (sess != null) {
                    if (sess.username != null) {
                        playerManager.savePlayerGameState(
                                sess.username, sess.currentIdGame,
                                sess.mistakes, sess.isGameOver,
                                sess.guessedCategoryNames);
                        playerManager.logout(sess.username);
                    }
                    activeSessions.remove(sess);
                }
                key.cancel();
            }
        });
    }

    /*
     Invia la classifica aggiornata a tutti i client via multicast
     insieme a un messaggio personalizzato (es. "Mario ha vinto").
     Chiamato da RequestHandler quando un giocatore vince o perde.
     */
    public static void mcastGameEnd(String individualMsg) {
        String leaderboard = playerManager.getLeaderboard(0, null);
        sendMCastMsg("[FINE PARTITA] " + individualMsg + "\n" + leaderboard);
    }

    /*
     Invia un messaggio UDP multicast al gruppo configurato.
     Ogni chiamata apre e chiude un MulticastSocket: non c'è uno stato persistente
     da mantenere lato server per il canale multicast.
     */
    public static void sendMCastMsg(String msg) {
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(1); // TTL=1: il pacchetto non esce dalla LAN locale
            InetAddress group = InetAddress.getByName(mcast_address);
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length, group, mcast_port);
            socket.send(pkt);
            System.out.println("[UDP SENT] " + msg);
        } catch (IOException e) {
            System.err.println("[UDP ERROR] Errore invio multicast: " + e.getMessage());
        }
    }

    public static Set<Session> getActiveSessions() { return activeSessions; }

    /*
     Procedura di shutdown ordinato: ferma il timer, drena il pool, chiude i canali.
     Chiamato dallo shutdown hook (CTRL+C) o da eventuali errori fatali.
     */
    private static void shutdown() {
        running = false;

        // Sveglia il selettore bloccato su select() così il while loop può uscire
        if (mainSelector != null) mainSelector.wakeup();

        // Aspetta che il timer finisca il ciclo corrente (max 5 secondi)
        if (gameTimer != null) {
            gameTimer.shutdown();
            try {
                if (!gameTimer.awaitTermination(5, TimeUnit.SECONDS))
                    gameTimer.shutdownNow();
            } catch (InterruptedException e) {
                gameTimer.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Aspetta che il pool finisca le richieste in corso (max 10 secondi)
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS))
                    pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (serverChannel != null) serverChannel.close();
            if (mainSelector != null)  mainSelector.close();
        } catch (IOException e) {
            System.err.println("[SHUTDOWN] Errore chiusura canali: " + e.getMessage());
        }

        sendMCastMsg("[SERVER] Server in spegnimento. Disconnessione imminente.");
        System.out.println("[SHUTDOWN] Server spento correttamente, alla prossima<3.");
    }
}
