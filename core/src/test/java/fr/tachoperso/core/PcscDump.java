package fr.tachoperso.core;

import fr.tachoperso.core.apdu.CardChannel;
import fr.tachoperso.core.tacho.CardDump;
import fr.tachoperso.core.tacho.DddFormat;
import fr.tachoperso.core.tacho.TachoCardDownloader;
import fr.tachoperso.core.util.Log;
import java.io.File;
import java.nio.file.Files;
import javax.smartcardio.Card;
import javax.smartcardio.CardTerminal;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.TerminalFactory;

/** Outil de mise au point : télécharge la carte via PC/SC et écrit un .DDD. */
public final class PcscDump {
    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "carte.DDD").getAbsoluteFile();
        CardTerminal terminal = null;
        for (CardTerminal t : TerminalFactory.getDefault().terminals().list()) {
            if (t.isCardPresent()) { terminal = t; break; }
        }
        if (terminal == null) throw new IllegalStateException("Aucun lecteur avec carte trouvé");
        System.out.println("Lecteur : " + terminal.getName());
        Card card = terminal.connect("*");
        System.out.println("Protocole : " + card.getProtocol());
        javax.smartcardio.CardChannel pcsc = card.getBasicChannel();
        Log log = System.out::println;
        CardChannel channel = new CardChannel(cmd -> {
            try { return pcsc.transmit(new CommandAPDU(cmd)).getBytes(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, false, log, false);
        CardDump dump = new TachoCardDownloader(channel, log, (s, f) -> kotlin.Unit.INSTANCE).download(true, card.getATR().getBytes());
        card.disconnect(false);
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), DddFormat.INSTANCE.write(dump));
        System.out.println("Écrit : " + out + " (" + out.length() + " octets), avertissements : " + dump.getWarnings());
    }
}
