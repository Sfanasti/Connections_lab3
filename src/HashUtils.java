import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/*
 Utility per l'hashing delle password.
 Usa SHA-512: le password non vengono mai salvate in chiaro.
 */
public class HashUtils {

    /*
     Restituisce l'hash SHA-512 della password in formato esadecimale.
     Lanciamo RuntimeException se SHA-512 non è disponibile
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] bytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b)); // ogni byte → 2 cifre hex
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Errore: SHA-512 non trovato!", e);
            }
    }
}
