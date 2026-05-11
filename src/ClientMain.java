import com.google.gson.Gson;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Scanner;

/*
  Punto di ingresso del client per il gioco Connections.

  Gestisce:
  - La connessione TCP al server tramite NIO SocketChannel (blocking mode lato client)
  - L'interfaccia a linea di comando per tutte le operazioni (register, login, play, ecc.)
  - Un thread demone separato che rimane in ascolto delle notifiche UDP multicast
  inviate dal server (fine partita, nuova partita, classifica)

  I parametri di connessione vengono letti da clientConfig.properties.
 */
public class ClientMain {
    private static final Gson gson = new Gson();
    private SocketChannel clientChannel;
    private Scanner scanner;
    private String currentUsername = null; // null = non loggato
    private boolean running = true;
    private String mcast_address;
    private int mcast_port;
    private String host;
    private int port;

    // Lista locale delle categorie già indovinate nella partita corrente
    // (usata solo per la visualizzazione nel client, non è lo stato ufficiale)
    private final java.util.List<String> guessedCategories = new java.util.ArrayList<>();

    // ===================== MAIN =====================

    public static void main(String[] args) {
        ClientMain client = new ClientMain();
        try {
            client.readConfig();
            client.start(client.host, client.port);
        } catch (IOException e) {
            System.err.println("[ERRORE CONFIG] " + e.getMessage());
        }
    }

    // ===================== CONFIG =====================

    /* Legge i parametri di connessione da clientConfig.properties. */
    private void readConfig() throws IOException {
        Properties p = new Properties();
        try (FileInputStream f = new FileInputStream("clientConfig.properties")) {
            p.load(f);
            this.host = p.getProperty("serverHost", "localhost");
            this.port = Integer.parseInt(p.getProperty("tcpPort","6969"));
            this.mcast_address = p.getProperty("mcast_address", "239.0.0.1");
            this.mcast_port = Integer.parseInt(p.getProperty("mcast_port",   "6789"));
        } catch (NumberFormatException e) {
            throw new IOException("Errore nei valori numerici del file config client.");
        }
    }

    // ===================== START =====================

    /*
     Avvia la connessione al server e il ciclo principale di input dell'utente.
     Lancia anche il thread demone per le notifiche multicast.
     */
    public void start(String host, int port) {
        scanner = new Scanner(System.in);
        try {
            clientChannel = SocketChannel.open(new InetSocketAddress(host, port));
            System.out.println("Connesso al server!");
            startNotificationsListener(); // avvia il listener multicast in background

            while (running) {
                printMenu();
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit")) {
                    running = false;
                    continue;
                }
                handleCommand(input);
            }

            clientChannel.close();
        } catch (IOException e) {
            System.err.println("Errore di rete: " + e.getMessage());
        }
    }

    // ===================== MENU =====================

    /* Mostra i comandi disponibili in base allo stato di login. */
    private void printMenu() {
        if (currentUsername == null) {
            System.out.println("==================================");
            System.out.println("           CONNECTIONS            ");
            System.out.println("==================================");
            System.out.println("\n------------- MENU -------------");
            System.out.println("Comandi: register, login, updateCredentials, exit");
        } else {
            System.out.println("\n--- UTENTE: " + currentUsername + " ---");
            System.out.println("Comandi: play, stats, leaderboard, gameinfo, gamestats, logout, exit");
        }
        System.out.print("> ");
    }

    // ===================== HANDLE COMMAND =====================

    /*
     Legge il comando dell'utente, raccoglie gli eventuali parametri interattivi,
     costruisce un oggetto Request, lo serializza in JSON e lo invia al server.
     Poi legge e mostra la risposta.
     */
    private void handleCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        cmd = cmd.trim().toLowerCase();

        // Blocca i comandi di gioco se non loggato, e i comandi di accesso se già loggato
        if (currentUsername == null) {
            if (!cmd.equals("register") && !cmd.equals("login")
                    && !cmd.equals("updatecredentials") && !cmd.equals("exit")) {
                System.out.println("Devi prima effettuare il login per usare '" + cmd + "'.");
                return;
            }
        } else {
            if (cmd.equals("register") || cmd.equals("login") || cmd.equals("updatecredentials")) {
                System.out.println("Sei già loggato come " + currentUsername + ". Fai prima il logout.");
                return;
            }
        }

        Request req = new Request();

        switch (cmd) {
            case "register":
                req.operation = "register";
                System.out.print("Username: "); req.username = scanner.nextLine();
                System.out.print("Password: "); req.psw = scanner.nextLine();
                break;

            case "login":
                req.operation = "login";
                System.out.print("Username: "); req.username = scanner.nextLine();
                System.out.print("Password: "); req.psw = scanner.nextLine();
                break;

            case "updatecredentials":
                req.operation = "updateCredentials";
                System.out.print("oldUsername: "); req.oldUsername = scanner.nextLine();
                System.out.print("oldPsw: "); req.oldPsw = scanner.nextLine();
                System.out.print("newUsername (invio = mantieni attuale): ");
                String nu = scanner.nextLine().trim();
                req.newUsername = nu.isEmpty() ? null : nu;
                System.out.print("newPsw (invio = mantieni attuale): ");
                String np = scanner.nextLine().trim();
                req.newPsw = np.isEmpty() ? null : np;
                break;

            case "play":
                req.operation = "submitProposal";
                System.out.println("Inserisci le 4 parole separate da spazio:");
                String line = scanner.nextLine();
                String[] parts = line.trim().toUpperCase().split("\\s+");
                if (parts.length != 4) {
                    System.out.println("Errore: devi inserire esattamente 4 parole.");
                    return;
                }
                req.words = new HashSet<>(Arrays.asList(parts));
                // Dopo la de-duplicazione del Set, potrebbero esserci meno di 4 parole
                // (l'utente ha inserito duplicati). Lo blocchiamo qui invece di mandare
                // una proposta malformata al server.
                if (req.words.size() != 4) {
                    System.out.println("Errore: non puoi inserire la stessa parola più volte.");
                    return;
                }
                break;

            case "stats":
                req.operation = "requestPlayerStats";
                break;

            case "leaderboard":
                req.operation = "requestLeaderboard";
                System.out.print("(1) Top K utenti  (2) Rank giocatore specifico [1/2]: ");
                String choice = scanner.nextLine().trim();
                if (choice.equals("1")) {
                    System.out.print("Inserisci K (0 per tutti): ");
                    try {
                        int k = Integer.parseInt(scanner.nextLine().trim());
                        if (k < 0) { System.out.println("K non valido."); return; }
                        req.topPlayers = k;
                    } catch (NumberFormatException e) { req.topPlayers = 0; }
                } else if (choice.equals("2")) {
                    System.out.print("Nome del giocatore: ");
                    req.playerName = scanner.nextLine();
                } else {
                    System.out.println("Scelta non valida.");
                    return;
                }
                break;

            case "gameinfo":
                req.operation = "requestGameInfo";
                System.out.print("ID partita (-1 = corrente): ");
                try { req.gameId = Integer.parseInt(scanner.nextLine()); }
                catch (NumberFormatException e) { System.out.println("Input non valido, uso partita corrente."); req.gameId = -1; }
                break;

            case "gamestats":
                req.operation = "requestGameStats";
                System.out.print("ID partita (-1 = corrente): ");
                try { req.gameId = Integer.parseInt(scanner.nextLine()); }
                catch (NumberFormatException e) { System.out.println("Input non valido, uso partita corrente."); req.gameId = -1; }
                break;

            case "logout":
                req.operation = "logout";
                break;

            default:
                System.out.println("Comando non valido.");
                return;
        }

        try {
            sendRequest(req);
            String response = readResponse();
            handleServerResponse(cmd, response, req);
        } catch (IOException e) {
            System.err.println("[ERRORE] Connessione persa: " + e.getMessage());
            running = false;
        }
    }

    // ===================== SEND / RECEIVE =====================

    /*
     Serializza la richiesta in JSON e la invia al server tramite il canale TCP.
     Aggiunge '\n' come terminatore di messaggio (il server legge fino a '\n').
     */
    private void sendRequest(Request req) throws IOException {
        String json = gson.toJson(req) + "\n";
        ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) clientChannel.write(buffer);
    }

    /*
     * Legge la risposta del server dal canale TCP.
     * Legge a blocchi finché non trova '\n' (terminatore di risposta).
     * Il canale è in modalità bloccante lato client, quindi read() attende i dati.
     */
    private String readResponse() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        StringBuilder sb = new StringBuilder();
        while (true) {
            buffer.clear();
            int n = clientChannel.read(buffer);
            if (n == -1) {
                running = false;
                throw new IOException("Server disconnesso.");
            }
            buffer.flip();
            String chunk = StandardCharsets.UTF_8.decode(buffer).toString();
            sb.append(chunk);
            if (chunk.contains("\n")) break; // messaggio completo ricevuto
        }
        return sb.toString().trim();
    }

    // ===================== HANDLE SERVER RESPONSE =====================

    /*
     Interpreta la risposta del server e la stampa in modo leggibile.
     Le risposte seguono il formato "CODICE|campo1:valore1|campo2:valore2|..."
     */
    private void handleServerResponse(String cmd, String response, Request req) {

        // LOGIN normale: partita ancora in corso per questo giocatore
        if (cmd.equalsIgnoreCase("login") && response.startsWith("LOGIN_SUCCESS")) {
            this.currentUsername = req.username;
            guessedCategories.clear();
            printGameState(response, "\n=== BENTORNATO! PARTITA IN CORSO ===");

        // LOGIN con partita già terminata (vinta o persa prima del logout)
        } else if (cmd.equalsIgnoreCase("login") && response.startsWith("LOGIN_GAME_OVER")) {
            this.currentUsername = req.username;
            guessedCategories.clear();
            printGameOverLogin(response);

        // REGISTER
        } else if (cmd.equalsIgnoreCase("register") && response.startsWith("REGISTRAZIONE_AVVENUTA")) {
            this.currentUsername = req.username;
            guessedCategories.clear();
            printGameState(response, "\n=== REGISTRAZIONE COMPLETATA — PARTITA IN CORSO ===");

        // LOGOUT
        } else if (cmd.equalsIgnoreCase("logout") && response.equals("LOGOUT_SUCCESS")) {
            this.currentUsername = null;
            guessedCategories.clear();
            System.out.println("Logout effettuato. Alla prossima!");

        // CATEGORIA CORRETTA (ma partita non ancora vinta)
        } else if (response.startsWith("CORRECT_GROUP|")) {
            String[] p = response.split("\\|");
            guessedCategories.add(p[1]);
            System.out.println("\nCategoria indovinata: " + p[1]);
            System.out.println("Trovate finora: " + String.join(", ", guessedCategories));
            if (p.length > 2 && !p[2].isEmpty())
                printRemainingGrid(p[2].split(","));

        // VITTORIA
        } else if (response.startsWith("WON_GAME|")) {
            String[] p = response.split("\\|");
            guessedCategories.add(p[1]);
            if (p.length > 2 && !p[2].isEmpty()) guessedCategories.add(p[2]);
            System.out.println("\n=== HAI VINTO! :) ===");
            System.out.println("Ultima categoria: " + p[1]);
            if (p.length > 2 && !p[2].isEmpty())
                System.out.println("Categoria automatica: " + p[2]); // quarta categoria implicita
            if (p.length > 3) System.out.println(p[3]);
            guessedCategories.clear();

        // SCONFITTA (4 errori raggiunto)
        } else if (response.startsWith("LOST_GAME|")) {
            System.out.println("\n=== HAI PERSO! :( ===");
            System.out.println(response.split("\\|")[1]);
            guessedCategories.clear();

        // TIMEOUT — la partita è scaduta mentre il giocatore era online
        } else if (response.startsWith("TIMEOUT|") || response.startsWith("TIME_EXPIRED|")) {
            guessedCategories.clear();
            System.out.println("\n[TEMPO SCADUTO]: " + response.split("\\|")[1]);
            // Il server può includere i dati della nuova partita nella stessa risposta
            if (response.contains("|NUOVA_PARTITA|")) {
                System.out.println("\n=== NUOVA PARTITA AVVIATA ===");
                for (String part : response.split("\\|")) {
                    if (part.startsWith("game:"))  System.out.println("Partita #" + part.substring(5));
                    if (part.startsWith("time:"))  System.out.println("Tempo rimasto: " + part.substring(5) + "s");
                    if (part.startsWith("words:")) printRemainingGrid(part.substring(6).split(","));
                }
                System.out.println("Usa 'play' per iniziare la nuova partita.");
            }

        // PAUSA tra partite
        } else if (response.startsWith("WAIT|") || response.startsWith("PAUSED|")) {
            System.out.println("\n[IN PAUSA]: " + response.split("\\|")[1]);

        // PROPOSTA SBAGLIATA (ma valida)
        } else if (response.startsWith("WRONG_PROPOSAL|")) {
            System.out.println("\n" + response.split("\\|")[1]);

        // PROPOSTA MALFORMATA (parole non valide, già usate, ecc.)
        } else if (response.startsWith("MALFORMED|")) {
            System.out.println("\nProposta non valida: " + response.split("\\|")[1]);

        // GAME INFO — partita in corso
        } else if (response.startsWith("GAME_INFO|")) {
            System.out.println("\n=== STATO PARTITA ===");
            for (String part : response.split("\\|")) {
                if (part.startsWith("game:"))   System.out.println("Partita #" + part.substring(5));
                if (part.startsWith("time:"))   System.out.println("Tempo: " + part.substring(5) + "s");
                if (part.startsWith("errors:")) System.out.println("Errori: " + part.substring(7) + "/4");
                if (part.startsWith("score:"))  System.out.println("Punteggio: " + part.substring(6));
                if (part.startsWith("found:") && !part.substring(6).isEmpty())
                    System.out.println("Trovate: " + part.substring(6).replace(";", ", "));
                if (part.startsWith("words:"))  printRemainingGrid(part.substring(6).split(","));
            }

        // GAME INFO — partita conclusa (mostra soluzione completa)
        } else if (response.startsWith("GAME_RESULT|")) {
            System.out.println("\n=== RISULTATO PARTITA ===");
            for (String part : response.split("\\|")) {
                if (part.startsWith("game:"))     System.out.println("Partita #" + part.substring(5));
                if (part.startsWith("found:"))    System.out.println("Categorie indovinate: " + part.substring(6));
                if (part.startsWith("errors:"))   System.out.println("Errori: " + part.substring(7));
                if (part.startsWith("score:"))    System.out.println("Punteggio: " + part.substring(6));
                if (part.startsWith("solution:")) {
                    System.out.println("Soluzione completa:");
                    for (String g : part.substring(9).split(";"))
                        if (!g.isEmpty()) System.out.println("  - " + g.replace("=", ": "));
                }
            }

        // GAME STATS — partita in corso
        } else if (response.startsWith("GAME_STATS_LIVE|")) {
            System.out.println("\n=== STATISTICHE PARTITA IN CORSO ===");
            for (String part : response.split("\\|")) {
                if (part.startsWith("game:"))     System.out.println("Partita #" + part.substring(5));
                if (part.startsWith("time:"))     System.out.println("Tempo rimanente: " + part.substring(5) + "s");
                if (part.startsWith("playing:"))  System.out.println("Ancora in gioco: " + part.substring(8));
                if (part.startsWith("finished:")) System.out.println("Hanno terminato: " + part.substring(9));
                if (part.startsWith("winners:"))  System.out.println("Vincitori: " + part.substring(8));
            }

        // GAME STATS — partita conclusa
        } else if (response.startsWith("GAME_STATS_FINAL|")) {
            System.out.println("\n=== STATISTICHE PARTITA CONCLUSA ===");
            for (String part : response.split("\\|")) {
                if (part.startsWith("game:"))         System.out.println("Partita #" + part.substring(5));
                if (part.startsWith("participants:"))  System.out.println("Partecipanti: " + part.substring(13));
                if (part.startsWith("finished:"))      System.out.println("Hanno terminato: " + part.substring(9));
                if (part.startsWith("winners:"))       System.out.println("Vincitori: " + part.substring(8));
                if (part.startsWith("avgScore:"))      System.out.println("Punteggio medio: " + part.substring(9));
            }

        // Risposta generica non gestita esplicitamente
        } else {
            System.out.println("\n[SERVER]: " + response);
        }
    }

    // ===================== METODI DI VISUALIZZAZIONE =====================

    /*
     Mostra l'esito al login quando il giocatore aveva già terminato la partita corrente.
     Il server invia LOGIN_GAME_OVER invece di LOGIN_SUCCESS.
     */
    private void printGameOverLogin(String response) {
        boolean won = false;
        String gameId = "?", timeLeft = "?", found = "0", errors = "0", score = "0";

        for (String part : response.split("\\|")) {
            if (part.startsWith("game:"))    gameId   = part.substring(5);
            if (part.startsWith("time:"))    timeLeft = part.substring(5);
            if (part.startsWith("outcome:")) won      = part.substring(8).equals("won");
            if (part.startsWith("found:"))   found    = part.substring(6);
            if (part.startsWith("errors:"))  errors   = part.substring(7);
            if (part.startsWith("score:"))   score    = part.substring(6);
        }

        System.out.println("\n=== BENTORNATO! HAI GIÀ " + (won ? "VINTO" : "PERSO")
                + " LA PARTITA #" + gameId + " ===");
        System.out.println("Categorie indovinate: " + found + " | Errori: " + errors
                + " | Punteggio: " + score);
        System.out.println("Tempo rimanente alla partita: " + timeLeft + "s");
        System.out.println("Attendi la prossima partita per giocare di nuovo.");

        // Mostra la soluzione completa così il giocatore può vedere i raggruppamenti
        for (String part : response.split("\\|")) {
            if (part.startsWith("solution:")) {
                System.out.println("Soluzione completa:");
                for (String g : part.substring(9).split(";"))
                    if (!g.isEmpty()) System.out.println("  - " + g.replace("=", ": "));
            }
        }
    }

    /* Stampa le informazioni di stato partita ricevute al login o alla registrazione. */
    private void printGameState(String response, String header) {
        System.out.println(header);
        for (String part : response.split("\\|")) {
            if (part.startsWith("game:"))   System.out.println("Partita #" + part.substring(5));
            if (part.startsWith("time:"))   System.out.println("Tempo rimanente: " + part.substring(5) + "s");
            if (part.startsWith("errors:")) System.out.println("Errori: " + part.substring(7) + "/4");
            if (part.startsWith("score:"))  System.out.println("Punteggio: " + part.substring(6));
            if (part.startsWith("found:") && !part.substring(6).isEmpty()) {
                for (String cat : part.substring(6).split(";"))
                    if (!cat.isEmpty()) guessedCategories.add(cat);
                System.out.println("Già indovinate: " + String.join(", ", guessedCategories));
            }
            if (part.startsWith("words:")) printRemainingGrid(part.substring(6).split(","));
        }
    }

    /* Stampa la griglia con le parole rimaste (dopo aver trovato una o più categorie). */
    private void printRemainingGrid(String[] words) {
        if (words == null || words.length == 0 || (words.length == 1 && words[0].isEmpty())) return;

        int cols = 4;
        // Calcola la lunghezza del separatore in base al numero di colonne
        String separator = new String(new char[cols * 18 + 1]).replace("\0", "-");

        System.out.println("\n--- PAROLE RIMANENTI ---");
        System.out.println(separator);
        for (int i = 0; i < words.length; i++) {
            System.out.printf("| %-15s ", words[i].trim());
            if ((i + 1) % cols == 0 || i == words.length - 1) {
                // Se l'ultima riga è incompleta, riempiamo le celle mancanti con spazi
                int rem = cols - ((i % cols) + 1);
                for (int j = 0; j < rem; j++) System.out.printf("| %-15s ", "");
                System.out.println("|");
                if (i < words.length - 1) System.out.println(separator);
            }
        }
        System.out.println(separator);
    }

    // ===================== MULTICAST LISTENER =====================

    /*
     Avvia un thread deamon che rimane in ascolto delle notifiche UDP multicast
     inviate dal server (fine partita, nuova partita, classifica, shutdown).

     La logica per trovare l'interfaccia di rete è necessaria perché su alcune
     macchine il sistema potrebbe scegliere l'interfaccia loopback (127.0.0.1) che
     non supporta il multicast su rete locale.
     */
    private void startNotificationsListener() {
        Thread listener = new Thread(() -> {
            try (MulticastSocket mSocket = new MulticastSocket(mcast_port)) {
                InetAddress group = InetAddress.getByName(mcast_address);
                InetSocketAddress groupAddress = new InetSocketAddress(group, mcast_port);

                // Cerca la prima interfaccia di rete non-loopback, attiva e con supporto multicast
                NetworkInterface netIf = null;
                java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp() || !ni.supportsMulticast()) continue;
                    netIf = ni;
                    break;
                }

                if (netIf == null) {
                    System.err.println("[MULTICAST] Nessuna interfaccia di rete valida trovata.");
                    return;
                }

                System.out.println("[MULTICAST] In ascolto su interfaccia: " + netIf.getName());
                mSocket.joinGroup(groupAddress, netIf);

                byte[] buffer = new byte[4096];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    mSocket.receive(packet); // bloccante: aspetta il prossimo messaggio
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                    // Se il server si sta spegnendo, chiudiamo il client automaticamente
                    if (msg.contains("Server in spegnimento")) {
                        System.out.println("\n[SERVER SPENTO] Il server si è disconnesso. Chiusura automatica...");
                        System.exit(0);
                    }
                    System.out.println("\n\n[NOTIFICA DAL SERVER]: " + msg);
                    System.out.print("> "); // ri-stampa il prompt dopo la notifica
                }

                mSocket.leaveGroup(groupAddress, netIf);
            } catch (IOException e) {
                if (running) System.err.println("[MULTICAST ERROR]: " + e.getMessage());
            }
        });

        listener.setDaemon(true); // si chiude automaticamente quando il main thread finisce
        listener.start();
    }
}
