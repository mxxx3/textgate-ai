package com.textgate.ai.tutorial

import android.content.Context
import com.textgate.ai.model.Languages
import com.textgate.ai.security.AppSettingsStore

data class TutorialSlideCopy(val title: String, val body: String)

data class TutorialCopy(
    val skip: String,
    val back: String,
    val next: String,
    val start: String,
    val replay: String,
    val slides: List<TutorialSlideCopy>
)

object TutorialCopyProvider {

    fun forContext(context: Context): TutorialCopy {
        val settings = AppSettingsStore(context)
        val code = settings.appInterfaceLanguage ?: Languages.DEFAULT.code
        return forCode(code)
    }

    internal fun forCode(code: String): TutorialCopy =
        COPIES[code] ?: COPIES.getValue("en")

    internal fun hasExplicitCopy(code: String): Boolean =
        COPIES.containsKey(code)

    private fun s(title: String, body: String) = TutorialSlideCopy(title, body)

    private fun c(
        skip: String,
        back: String,
        next: String,
        start: String,
        replay: String,
        vararg slides: TutorialSlideCopy
    ) = TutorialCopy(skip, back, next, start, replay, slides.toList())

    private val COPIES: Map<String, TutorialCopy> = mapOf(
        "en" to c(
            "Skip", "Back", "Next", "Start using TextGate AI", "Show tutorial again",
            s("Welcome to TextGate AI", "Translate typed text, received messages and speech. This quick tour shows the main ways to use TextGate AI."),
            s("Translate text", "Use Translate for normal text. Choose languages, type or dictate, then copy the result or listen to it."),
            s("Translate while you type", "In allowed messaging and social apps, end your message with a trigger such as ?en or ?de. TextGate replaces it with the translation before you send."),
            s("Translate received messages", "In supported apps, long-press a message or comment. TextGate shows a temporary translation bubble without changing the original."),
            s("Conversation and Live", "Use Conversation for spoken back-and-forth. Use Live to keep listening and translate surrounding speech through your headset or speaker."),
            s("Private by design", "TextGate blocks password and other sensitive fields, encrypts your Gemini API key on this device, and lets you control which apps may use it. You are ready.")
        ),
        "pl" to c(
            "Pomiń", "Wstecz", "Dalej", "Zacznij używać TextGate AI", "Pokaż tutorial ponownie",
            s("Witaj w TextGate AI", "Tłumacz tekst, który piszesz, otrzymane wiadomości i mowę. Ta krótka prezentacja pokaże główne sposoby korzystania z TextGate AI."),
            s("Tłumacz tekst", "Użyj zakładki Tłumacz do zwykłego tekstu. Wybierz języki, wpisz lub podyktuj treść, a potem skopiuj wynik albo go odsłuchaj."),
            s("Tłumacz podczas pisania", "W dozwolonych komunikatorach i aplikacjach społecznościowych zakończ wiadomość wyzwalaczem, np. ?en lub ?de. TextGate podmieni go na tłumaczenie przed wysłaniem."),
            s("Tłumacz odebrane wiadomości", "W obsługiwanych aplikacjach przytrzymaj wiadomość lub komentarz. TextGate pokaże tymczasowy dymek z tłumaczeniem bez zmiany oryginału."),
            s("Rozmowa i Na żywo", "Użyj Rozmowy do tłumaczenia dialogu mówionego. Tryb Na żywo może stale słuchać i tłumaczyć mowę z otoczenia przez słuchawki lub głośnik."),
            s("Prywatność w projekcie", "TextGate blokuje hasła i inne wrażliwe pola, szyfruje klucz Gemini API na tym urządzeniu i pozwala Ci wybierać aplikacje, w których działa. Wszystko gotowe.")
        ),
        "de" to c(
            "Überspringen", "Zurück", "Weiter", "TextGate AI verwenden", "Tutorial erneut anzeigen",
            s("Willkommen bei TextGate AI", "Übersetze getippten Text, empfangene Nachrichten und Sprache. Diese kurze Tour zeigt dir die wichtigsten Einsatzmöglichkeiten von TextGate AI."),
            s("Text übersetzen", "Nutze Übersetzen für normalen Text. Wähle die Sprachen, tippe oder diktiere und kopiere das Ergebnis oder höre es dir an."),
            s("Beim Tippen übersetzen", "Beende deine Nachricht in erlaubten Messenger- und Social-Apps mit einem Kürzel wie ?en oder ?de. TextGate ersetzt es vor dem Senden durch die Übersetzung."),
            s("Empfangene Nachrichten übersetzen", "Halte in unterstützten Apps eine Nachricht oder einen Kommentar gedrückt. TextGate zeigt eine vorübergehende Übersetzungsblase, ohne den Originaltext zu verändern."),
            s("Gespräch und Live", "Nutze Gespräch für gesprochene Dialoge. Live kann weiter zuhören und Sprache aus deiner Umgebung über Kopfhörer oder Lautsprecher übersetzen."),
            s("Privatsphäre von Anfang an", "TextGate blockiert Passwörter und andere sensible Felder, verschlüsselt deinen Gemini-API-Schlüssel auf diesem Gerät und lässt dich festlegen, in welchen Apps es aktiv ist. Du bist startklar.")
        ),
        "fr" to c(
            "Ignorer", "Retour", "Suivant", "Commencer avec TextGate AI", "Afficher à nouveau le tutoriel",
            s("Bienvenue dans TextGate AI", "Traduisez le texte saisi, les messages reçus et la parole. Ce guide rapide présente les principales façons d’utiliser TextGate AI."),
            s("Traduire du texte", "Utilisez Traduire pour du texte classique. Choisissez les langues, saisissez ou dictez le texte, puis copiez le résultat ou écoutez-le."),
            s("Traduire pendant la saisie", "Dans les messageries et réseaux sociaux autorisés, terminez votre message par un déclencheur comme ?en ou ?de. TextGate le remplace par la traduction avant l’envoi."),
            s("Traduire les messages reçus", "Dans les applications compatibles, maintenez un message ou un commentaire. TextGate affiche une bulle de traduction temporaire sans modifier l’original."),
            s("Conversation et Live", "Utilisez Conversation pour les échanges parlés. Live peut continuer à écouter et traduire les paroles autour de vous via le casque ou le haut-parleur."),
            s("Confidentialité intégrée", "TextGate bloque les mots de passe et autres champs sensibles, chiffre votre clé API Gemini sur cet appareil et vous laisse choisir les applications autorisées. Vous êtes prêt.")
        ),
        "es" to c(
            "Omitir", "Atrás", "Siguiente", "Empezar a usar TextGate AI", "Mostrar el tutorial de nuevo",
            s("Bienvenido a TextGate AI", "Traduce texto escrito, mensajes recibidos y voz. Este recorrido rápido muestra las formas principales de usar TextGate AI."),
            s("Traducir texto", "Usa Traducir para texto normal. Elige los idiomas, escribe o dicta y después copia el resultado o escúchalo."),
            s("Traducir mientras escribes", "En apps de mensajería y redes sociales permitidas, termina el mensaje con un activador como ?en o ?de. TextGate lo sustituye por la traducción antes de enviarlo."),
            s("Traducir mensajes recibidos", "En apps compatibles, mantén pulsado un mensaje o comentario. TextGate muestra una burbuja temporal con la traducción sin cambiar el original."),
            s("Conversación y Live", "Usa Conversación para hablar por turnos. Live puede seguir escuchando y traducir el habla de tu entorno mediante auriculares o altavoz."),
            s("Privacidad desde el diseño", "TextGate bloquea contraseñas y otros campos sensibles, cifra tu clave API de Gemini en este dispositivo y te deja controlar en qué apps funciona. Ya está todo listo.")
        ),
        "it" to c(
            "Salta", "Indietro", "Avanti", "Inizia a usare TextGate AI", "Mostra di nuovo il tutorial",
            s("Benvenuto in TextGate AI", "Traduci testo digitato, messaggi ricevuti e voce. Questo breve tour mostra i modi principali per usare TextGate AI."),
            s("Traduci testo", "Usa Traduci per il testo normale. Scegli le lingue, scrivi o detta, poi copia il risultato o ascoltalo."),
            s("Traduci mentre scrivi", "Nelle app di messaggistica e social consentite, termina il messaggio con un comando come ?en o ?de. TextGate lo sostituisce con la traduzione prima dell’invio."),
            s("Traduci i messaggi ricevuti", "Nelle app supportate, tieni premuto un messaggio o un commento. TextGate mostra una bolla temporanea con la traduzione senza modificare l’originale."),
            s("Conversazione e Live", "Usa Conversazione per dialoghi vocali a turni. Live può continuare ad ascoltare e tradurre ciò che viene detto intorno a te tramite cuffie o altoparlante."),
            s("Privacy integrata", "TextGate blocca password e altri campi sensibili, cifra la chiave API Gemini su questo dispositivo e ti permette di scegliere in quali app può funzionare. Sei pronto.")
        ),
        "pt" to c(
            "Ignorar", "Voltar", "Seguinte", "Começar a usar o TextGate AI", "Mostrar o tutorial novamente",
            s("Bem-vindo ao TextGate AI", "Traduza texto escrito, mensagens recebidas e fala. Este guia rápido mostra as principais formas de usar o TextGate AI."),
            s("Traduzir texto", "Use Traduzir para texto normal. Escolha os idiomas, escreva ou dite e depois copie o resultado ou ouça-o."),
            s("Traduzir enquanto escreve", "Nas aplicações de mensagens e redes sociais permitidas, termine a mensagem com um acionador como ?en ou ?de. O TextGate substitui-o pela tradução antes de enviar."),
            s("Traduzir mensagens recebidas", "Nas aplicações compatíveis, mantenha uma mensagem ou comentário premido. O TextGate mostra uma bolha temporária com a tradução sem alterar o original."),
            s("Conversa e Live", "Use Conversa para diálogos falados. O Live pode continuar a ouvir e traduzir a fala à sua volta pelos auscultadores ou altifalante."),
            s("Privacidade desde o início", "O TextGate bloqueia palavras-passe e outros campos sensíveis, encripta a sua chave API Gemini neste dispositivo e permite controlar em que aplicações funciona. Está tudo pronto.")
        ),
        "pt-rBR" to c(
            "Pular", "Voltar", "Avançar", "Começar a usar o TextGate AI", "Mostrar o tutorial novamente",
            s("Bem-vindo ao TextGate AI", "Traduza texto digitado, mensagens recebidas e fala. Este guia rápido mostra as principais formas de usar o TextGate AI."),
            s("Traduzir texto", "Use Traduzir para texto comum. Escolha os idiomas, digite ou dite e depois copie o resultado ou ouça a tradução."),
            s("Traduzir enquanto digita", "Nos apps de mensagens e redes sociais permitidos, termine a mensagem com um gatilho como ?en ou ?de. O TextGate substitui o gatilho pela tradução antes do envio."),
            s("Traduzir mensagens recebidas", "Em apps compatíveis, mantenha uma mensagem ou comentário pressionado. O TextGate mostra um balão temporário com a tradução sem alterar o original."),
            s("Conversa e Live", "Use Conversa para diálogos falados. O Live pode continuar ouvindo e traduzir a fala ao seu redor pelos fones de ouvido ou alto-falante."),
            s("Privacidade desde o projeto", "O TextGate bloqueia senhas e outros campos sensíveis, criptografa sua chave API do Gemini neste dispositivo e permite escolher em quais apps ele funciona. Tudo pronto.")
        ),
        "nl" to c(
            "Overslaan", "Terug", "Volgende", "TextGate AI gaan gebruiken", "Tutorial opnieuw tonen",
            s("Welkom bij TextGate AI", "Vertaal getypte tekst, ontvangen berichten en spraak. Deze korte rondleiding laat de belangrijkste manieren zien om TextGate AI te gebruiken."),
            s("Tekst vertalen", "Gebruik Vertalen voor gewone tekst. Kies talen, typ of dicteer en kopieer daarna het resultaat of luister ernaar."),
            s("Vertalen terwijl je typt", "Eindig je bericht in toegestane chat- en sociale apps met een trigger zoals ?en of ?de. TextGate vervangt die vóór het verzenden door de vertaling."),
            s("Ontvangen berichten vertalen", "Houd in ondersteunde apps een bericht of reactie ingedrukt. TextGate toont tijdelijk een vertaalballon zonder het origineel te wijzigen."),
            s("Gesprek en Live", "Gebruik Gesprek voor gesproken heen-en-weer gesprekken. Live kan blijven luisteren en spraak om je heen vertalen via je headset of luidspreker."),
            s("Privacy ingebouwd", "TextGate blokkeert wachtwoorden en andere gevoelige velden, versleutelt je Gemini API-sleutel op dit apparaat en laat jou bepalen in welke apps het werkt. Je bent klaar.")
        ),
        "da" to c(
            "Spring over", "Tilbage", "Næste", "Begynd at bruge TextGate AI", "Vis vejledningen igen",
            s("Velkommen til TextGate AI", "Oversæt tekst, du skriver, modtagne beskeder og tale. Denne korte rundvisning viser de vigtigste måder at bruge TextGate AI på."),
            s("Oversæt tekst", "Brug Oversæt til almindelig tekst. Vælg sprog, skriv eller dikter, og kopiér derefter resultatet eller lyt til det."),
            s("Oversæt mens du skriver", "I tilladte besked- og sociale apps kan du afslutte beskeden med en udløser som ?en eller ?de. TextGate erstatter den med oversættelsen før afsendelse."),
            s("Oversæt modtagne beskeder", "I understøttede apps kan du holde en besked eller kommentar nede. TextGate viser en midlertidig oversættelsesboble uden at ændre originalen."),
            s("Samtale og Live", "Brug Samtale til talte dialoger. Live kan fortsætte med at lytte og oversætte tale omkring dig via headset eller højttaler."),
            s("Privatliv fra starten", "TextGate blokerer adgangskoder og andre følsomme felter, krypterer din Gemini API-nøgle på denne enhed og lader dig styre, hvilke apps det virker i. Du er klar.")
        ),
        "nb" to c(
            "Hopp over", "Tilbake", "Neste", "Begynn å bruke TextGate AI", "Vis veiledningen på nytt",
            s("Velkommen til TextGate AI", "Oversett tekst du skriver, mottatte meldinger og tale. Denne korte omvisningen viser de viktigste måtene å bruke TextGate AI på."),
            s("Oversett tekst", "Bruk Oversett for vanlig tekst. Velg språk, skriv eller dikter, og kopier deretter resultatet eller lytt til det."),
            s("Oversett mens du skriver", "I tillatte meldings- og sosiale apper kan du avslutte meldingen med en utløser som ?en eller ?de. TextGate erstatter den med oversettelsen før du sender."),
            s("Oversett mottatte meldinger", "I støttede apper kan du holde inne en melding eller kommentar. TextGate viser en midlertidig oversettelsesboble uten å endre originalen."),
            s("Samtale og Live", "Bruk Samtale for muntlige dialoger. Live kan fortsette å lytte og oversette tale rundt deg via hodesett eller høyttaler."),
            s("Personvern innebygd", "TextGate blokkerer passord og andre sensitive felt, krypterer Gemini API-nøkkelen på denne enheten og lar deg styre hvilke apper den kan brukes i. Du er klar.")
        ),
        "fi" to c(
            "Ohita", "Takaisin", "Seuraava", "Aloita TextGate AI:n käyttö", "Näytä opastus uudelleen",
            s("Tervetuloa TextGate AI:hin", "Käännä kirjoittamaasi tekstiä, vastaanotettuja viestejä ja puhetta. Tämä lyhyt opastus näyttää tärkeimmät käyttötavat."),
            s("Käännä tekstiä", "Käytä Käännä-näkymää tavalliseen tekstiin. Valitse kielet, kirjoita tai sanele ja kopioi tulos tai kuuntele se."),
            s("Käännä kirjoittaessasi", "Lisää sallituissa viesti- ja some-sovelluksissa viestin loppuun tunniste, kuten ?en tai ?de. TextGate korvaa sen käännöksellä ennen lähettämistä."),
            s("Käännä vastaanotetut viestit", "Paina tuetuissa sovelluksissa viestiä tai kommenttia pitkään. TextGate näyttää tilapäisen käännöskuplan muuttamatta alkuperäistä."),
            s("Keskustelu ja Live", "Käytä Keskustelua puhuttuun vuoropuheluun. Live voi kuunnella jatkuvasti ja kääntää ympärilläsi kuuluvaa puhetta kuulokkeisiin tai kaiuttimeen."),
            s("Yksityisyys mukana alusta asti", "TextGate estää salasanat ja muut arkaluonteiset kentät, salaa Gemini API -avaimesi tällä laitteella ja antaa sinun hallita, missä sovelluksissa se toimii. Kaikki on valmista.")
        ),
        "sv" to c(
            "Hoppa över", "Tillbaka", "Nästa", "Börja använda TextGate AI", "Visa guiden igen",
            s("Välkommen till TextGate AI", "Översätt text du skriver, mottagna meddelanden och tal. Den här snabba rundturen visar de viktigaste sätten att använda TextGate AI."),
            s("Översätt text", "Använd Översätt för vanlig text. Välj språk, skriv eller diktera och kopiera sedan resultatet eller lyssna på det."),
            s("Översätt medan du skriver", "I tillåtna meddelande- och sociala appar kan du avsluta meddelandet med en utlösare som ?en eller ?de. TextGate ersätter den med översättningen före sändning."),
            s("Översätt mottagna meddelanden", "I appar som stöds kan du hålla ned ett meddelande eller en kommentar. TextGate visar en tillfällig översättningsbubbla utan att ändra originalet."),
            s("Samtal och Live", "Använd Samtal för talade dialoger. Live kan fortsätta lyssna och översätta tal omkring dig via headset eller högtalare."),
            s("Integritet från början", "TextGate blockerar lösenord och andra känsliga fält, krypterar din Gemini API-nyckel på den här enheten och låter dig styra vilka appar som får använda det. Du är redo.")
        ),
        "cs" to c(
            "Přeskočit", "Zpět", "Další", "Začít používat TextGate AI", "Zobrazit návod znovu",
            s("Vítejte v TextGate AI", "Překládejte psaný text, přijaté zprávy i řeč. Tato krátká prohlídka ukáže hlavní způsoby použití TextGate AI."),
            s("Překlad textu", "Pro běžný text použijte Překladač. Vyberte jazyky, napište nebo nadiktujte text a výsledek zkopírujte nebo si jej poslechněte."),
            s("Překlad při psaní", "V povolených komunikačních a sociálních aplikacích ukončete zprávu spouštěčem, například ?en nebo ?de. TextGate jej před odesláním nahradí překladem."),
            s("Překlad přijatých zpráv", "V podporovaných aplikacích dlouze stiskněte zprávu nebo komentář. TextGate zobrazí dočasnou bublinu s překladem bez změny originálu."),
            s("Konverzace a Live", "Konverzaci použijte pro mluvený dialog. Live může dál poslouchat a překládat řeč kolem vás do sluchátek nebo reproduktoru."),
            s("Soukromí už v návrhu", "TextGate blokuje hesla a další citlivá pole, šifruje váš klíč Gemini API v tomto zařízení a umožňuje určit, ve kterých aplikacích smí fungovat. Vše je připraveno.")
        ),
        "sk" to c(
            "Preskočiť", "Späť", "Ďalej", "Začať používať TextGate AI", "Zobraziť návod znova",
            s("Vitajte v TextGate AI", "Prekladajte písaný text, prijaté správy aj reč. Táto krátka prehliadka ukáže hlavné možnosti používania TextGate AI."),
            s("Preklad textu", "Na bežný text použite Prekladač. Vyberte jazyky, napíšte alebo nadiktujte text a výsledok skopírujte alebo si ho vypočujte."),
            s("Preklad počas písania", "V povolených komunikačných a sociálnych aplikáciách ukončite správu spúšťačom, napríklad ?en alebo ?de. TextGate ho pred odoslaním nahradí prekladom."),
            s("Preklad prijatých správ", "V podporovaných aplikáciách podržte správu alebo komentár. TextGate zobrazí dočasnú bublinu s prekladom bez zmeny originálu."),
            s("Konverzácia a Live", "Konverzáciu použite na hovorený dialóg. Live môže ďalej počúvať a prekladať reč okolo vás cez slúchadlá alebo reproduktor."),
            s("Súkromie už v návrhu", "TextGate blokuje heslá a ďalšie citlivé polia, šifruje váš kľúč Gemini API v tomto zariadení a umožňuje určiť, v ktorých aplikáciách smie fungovať. Všetko je pripravené.")
        ),
        "hu" to c(
            "Kihagyás", "Vissza", "Tovább", "TextGate AI használatának megkezdése", "Bemutató újbóli megjelenítése",
            s("Üdvözlünk a TextGate AI-ban", "Fordítsd le a begépelt szöveget, a kapott üzeneteket és a beszédet. Ez a rövid bemutató megmutatja a fő használati módokat."),
            s("Szöveg fordítása", "A Fordítás lapon hagyományos szöveget fordíthatsz. Válassz nyelveket, írj vagy diktálj, majd másold ki vagy hallgasd meg az eredményt."),
            s("Fordítás gépelés közben", "Az engedélyezett üzenetküldő és közösségi appokban zárd az üzenetet például ?en vagy ?de jellel. A TextGate küldés előtt a fordításra cseréli."),
            s("Kapott üzenetek fordítása", "Támogatott appokban tarts hosszan lenyomva egy üzenetet vagy hozzászólást. A TextGate ideiglenes fordítási buborékot mutat az eredeti módosítása nélkül."),
            s("Beszélgetés és Live", "A Beszélgetés mód beszélt párbeszédhez való. A Live folyamatosan hallgathatja és fordíthatja a környező beszédet fejhallgatón vagy hangszórón."),
            s("Adatvédelem alapból", "A TextGate blokkolja a jelszó- és más érzékeny mezőket, ezen az eszközön titkosítja a Gemini API-kulcsot, és te döntöd el, mely appokban működhet. Készen állsz.")
        ),
        "ro" to c(
            "Omite", "Înapoi", "Înainte", "Începe să folosești TextGate AI", "Arată din nou tutorialul",
            s("Bun venit în TextGate AI", "Tradu textul tastat, mesajele primite și vorbirea. Acest tur scurt îți arată principalele moduri de folosire a TextGate AI."),
            s("Tradu text", "Folosește Traducere pentru text obișnuit. Alege limbile, scrie sau dictează, apoi copiază rezultatul ori ascultă-l."),
            s("Tradu în timp ce scrii", "În aplicațiile de mesagerie și sociale permise, încheie mesajul cu un declanșator precum ?en sau ?de. TextGate îl înlocuiește cu traducerea înainte de trimitere."),
            s("Tradu mesajele primite", "În aplicațiile compatibile, ține apăsat un mesaj sau comentariu. TextGate afișează temporar o bulă cu traducerea fără a modifica originalul."),
            s("Conversație și Live", "Folosește Conversație pentru dialoguri vorbite. Live poate continua să asculte și să traducă vorbirea din jur prin căști sau difuzor."),
            s("Confidențialitate din proiectare", "TextGate blochează parolele și alte câmpuri sensibile, criptează cheia API Gemini pe acest dispozitiv și îți permite să alegi în ce aplicații funcționează. Ești gata.")
        ),
        "bg" to c(
            "Пропускане", "Назад", "Напред", "Започнете с TextGate AI", "Покажи урока отново",
            s("Добре дошли в TextGate AI", "Превеждайте въведен текст, получени съобщения и реч. Тази кратка обиколка показва основните начини за използване на TextGate AI."),
            s("Превод на текст", "Използвайте Превод за обикновен текст. Изберете езици, въведете или продиктувайте и после копирайте резултата или го чуйте."),
            s("Превод докато пишете", "В разрешени приложения за съобщения и социални мрежи завършете текста с код като ?en или ?de. TextGate го заменя с превода преди изпращане."),
            s("Превод на получени съобщения", "В поддържани приложения задръжте съобщение или коментар. TextGate показва временен балон с превода, без да променя оригинала."),
            s("Разговор и Live", "Използвайте Разговор за устен диалог. Live може да продължи да слуша и да превежда речта около вас през слушалки или високоговорител."),
            s("Поверителност по замисъл", "TextGate блокира пароли и други чувствителни полета, криптира вашия Gemini API ключ на това устройство и ви дава контрол в кои приложения работи. Готови сте.")
        ),
        "hr" to c(
            "Preskoči", "Natrag", "Dalje", "Počni koristiti TextGate AI", "Ponovno prikaži vodič",
            s("Dobro došli u TextGate AI", "Prevodite tekst koji pišete, primljene poruke i govor. Ovaj kratki vodič pokazuje glavne načine korištenja TextGate AI-ja."),
            s("Prevedi tekst", "Koristite Prevoditelj za običan tekst. Odaberite jezike, upišite ili diktirajte, zatim kopirajte rezultat ili ga poslušajte."),
            s("Prevodite dok pišete", "U dopuštenim aplikacijama za poruke i društvenim mrežama završite poruku okidačem poput ?en ili ?de. TextGate ga prije slanja zamjenjuje prijevodom."),
            s("Prevedi primljene poruke", "U podržanim aplikacijama dugo pritisnite poruku ili komentar. TextGate prikazuje privremeni oblačić s prijevodom bez promjene izvornika."),
            s("Razgovor i Live", "Koristite Razgovor za govorni dijalog. Live može nastaviti slušati i prevoditi govor oko vas putem slušalica ili zvučnika."),
            s("Privatnost po dizajnu", "TextGate blokira lozinke i druga osjetljiva polja, šifrira vaš Gemini API ključ na ovom uređaju i daje vam kontrolu nad aplikacijama u kojima radi. Spremni ste.")
        ),
        "sl" to c(
            "Preskoči", "Nazaj", "Naprej", "Začni uporabljati TextGate AI", "Znova prikaži vadnico",
            s("Dobrodošli v TextGate AI", "Prevajajte vtipkano besedilo, prejeta sporočila in govor. Ta kratek vodnik pokaže glavne načine uporabe TextGate AI."),
            s("Prevedi besedilo", "Za navadno besedilo uporabite Prevajanje. Izberite jezika, vtipkajte ali narekujte, nato rezultat kopirajte ali poslušajte."),
            s("Prevajaj med pisanjem", "V dovoljenih aplikacijah za sporočila in družabnih omrežjih končajte sporočilo s sprožilcem, kot je ?en ali ?de. TextGate ga pred pošiljanjem zamenja s prevodom."),
            s("Prevedi prejeta sporočila", "V podprtih aplikacijah pridržite sporočilo ali komentar. TextGate prikaže začasen oblaček s prevodom brez spreminjanja izvirnika."),
            s("Pogovor in Live", "Pogovor uporabite za govorjene dialoge. Live lahko neprekinjeno posluša in prevaja govor okoli vas prek slušalk ali zvočnika."),
            s("Zasebnost po zasnovi", "TextGate blokira gesla in druga občutljiva polja, šifrira vaš ključ Gemini API v tej napravi in vam omogoča nadzor nad aplikacijami, v katerih deluje. Pripravljeni ste.")
        ),
        "sr" to c(
            "Прескочи", "Назад", "Даље", "Почни да користиш TextGate AI", "Поново прикажи водич",
            s("Добро дошли у TextGate AI", "Преводите текст који куцате, примљене поруке и говор. Овај кратки водич показује главне начине коришћења TextGate AI."),
            s("Преведи текст", "Користите Превод за обичан текст. Изаберите језике, куцајте или диктирајте, а затим копирајте резултат или га преслушајте."),
            s("Преводи док куцаш", "У дозвољеним апликацијама за поруке и друштвеним мрежама завршите поруку окидачем као што је ?en или ?de. TextGate га пре слања замењује преводом."),
            s("Преведи примљене поруке", "У подржаним апликацијама дуго притисните поруку или коментар. TextGate приказује привремени облачић са преводом без измене оригинала."),
            s("Разговор и Live", "Користите Разговор за говорни дијалог. Live може наставити да слуша и преводи говор око вас преко слушалица или звучника."),
            s("Приватност по дизајну", "TextGate блокира лозинке и друга осетљива поља, шифрује ваш Gemini API кључ на овом уређају и даје вам контролу над апликацијама у којима ради. Спремни сте.")
        ),
        "ru" to c(
            "Пропустить", "Назад", "Далее", "Начать использовать TextGate AI", "Показать обучение снова",
            s("Добро пожаловать в TextGate AI", "Переводите набираемый текст, полученные сообщения и речь. Этот короткий обзор покажет основные способы использования TextGate AI."),
            s("Перевод текста", "Используйте Перевод для обычного текста. Выберите языки, введите или продиктуйте текст, затем скопируйте результат или прослушайте его."),
            s("Перевод во время набора", "В разрешённых мессенджерах и соцсетях завершите сообщение триггером, например ?en или ?de. TextGate заменит его переводом перед отправкой."),
            s("Перевод полученных сообщений", "В поддерживаемых приложениях нажмите и удерживайте сообщение или комментарий. TextGate покажет временное окно с переводом, не изменяя оригинал."),
            s("Разговор и Live", "Используйте Разговор для устного диалога. Live может продолжать слушать и переводить речь вокруг вас через наушники или динамик."),
            s("Конфиденциальность по замыслу", "TextGate блокирует пароли и другие чувствительные поля, шифрует ваш ключ Gemini API на этом устройстве и позволяет выбирать приложения, где он работает. Всё готово.")
        ),
        "uk" to c(
            "Пропустити", "Назад", "Далі", "Почати користуватися TextGate AI", "Показати навчання знову",
            s("Ласкаво просимо до TextGate AI", "Перекладайте набраний текст, отримані повідомлення та мовлення. Цей короткий огляд покаже основні способи використання TextGate AI."),
            s("Переклад тексту", "Використовуйте Переклад для звичайного тексту. Виберіть мови, введіть або продиктуйте текст, а потім скопіюйте результат чи прослухайте його."),
            s("Переклад під час набору", "У дозволених месенджерах і соцмережах завершіть повідомлення тригером, наприклад ?en або ?de. TextGate замінить його перекладом перед надсиланням."),
            s("Переклад отриманих повідомлень", "У підтримуваних застосунках натисніть і утримуйте повідомлення або коментар. TextGate покаже тимчасову бульбашку з перекладом, не змінюючи оригінал."),
            s("Розмова і Live", "Використовуйте Розмову для усного діалогу. Live може продовжувати слухати й перекладати мовлення навколо вас через навушники або динамік."),
            s("Конфіденційність за задумом", "TextGate блокує паролі та інші чутливі поля, шифрує ваш ключ Gemini API на цьому пристрої й дозволяє контролювати, у яких застосунках він працює. Усе готово.")
        ),
        "el" to c(
            "Παράλειψη", "Πίσω", "Επόμενο", "Έναρξη χρήσης TextGate AI", "Εμφάνιση οδηγού ξανά",
            s("Καλώς ήρθατε στο TextGate AI", "Μεταφράστε κείμενο που πληκτρολογείτε, ληφθέντα μηνύματα και ομιλία. Αυτή η σύντομη περιήγηση δείχνει τους βασικούς τρόπους χρήσης του TextGate AI."),
            s("Μετάφραση κειμένου", "Χρησιμοποιήστε τη Μετάφραση για απλό κείμενο. Επιλέξτε γλώσσες, πληκτρολογήστε ή υπαγορεύστε και μετά αντιγράψτε το αποτέλεσμα ή ακούστε το."),
            s("Μετάφραση ενώ γράφετε", "Σε επιτρεπόμενες εφαρμογές μηνυμάτων και κοινωνικών δικτύων, τελειώστε το μήνυμα με έναν δείκτη όπως ?en ή ?de. Το TextGate τον αντικαθιστά με τη μετάφραση πριν την αποστολή."),
            s("Μετάφραση ληφθέντων μηνυμάτων", "Σε υποστηριζόμενες εφαρμογές, πατήστε παρατεταμένα ένα μήνυμα ή σχόλιο. Το TextGate εμφανίζει προσωρινό συννεφάκι μετάφρασης χωρίς να αλλάζει το πρωτότυπο."),
            s("Συνομιλία και Live", "Χρησιμοποιήστε τη Συνομιλία για προφορικό διάλογο. Το Live μπορεί να συνεχίσει να ακούει και να μεταφράζει την ομιλία γύρω σας μέσω ακουστικών ή ηχείου."),
            s("Απόρρητο από τον σχεδιασμό", "Το TextGate αποκλείει κωδικούς πρόσβασης και άλλα ευαίσθητα πεδία, κρυπτογραφεί το κλειδί Gemini API σε αυτή τη συσκευή και σας επιτρέπει να ελέγχετε σε ποιες εφαρμογές λειτουργεί. Είστε έτοιμοι.")
        ),
        "tr" to c(
            "Atla", "Geri", "İleri", "TextGate AI kullanmaya başla", "Eğitimi tekrar göster",
            s("TextGate AI’ye hoş geldiniz", "Yazdığınız metni, aldığınız mesajları ve konuşmayı çevirin. Bu kısa tur TextGate AI’yi kullanmanın temel yollarını gösterir."),
            s("Metin çevir", "Normal metin için Çeviri bölümünü kullanın. Dilleri seçin, yazın veya dikte edin; ardından sonucu kopyalayın ya da dinleyin."),
            s("Yazarken çevir", "İzin verilen mesajlaşma ve sosyal uygulamalarda mesajınızı ?en veya ?de gibi bir tetikleyiciyle bitirin. TextGate göndermeden önce bunu çeviriyle değiştirir."),
            s("Gelen mesajları çevir", "Desteklenen uygulamalarda bir mesajı veya yorumu basılı tutun. TextGate özgün metni değiştirmeden geçici bir çeviri balonu gösterir."),
            s("Konuşma ve Live", "Sözlü karşılıklı konuşmalar için Konuşma’yı kullanın. Live çevrenizdeki konuşmayı dinlemeye ve kulaklık ya da hoparlör üzerinden çevirmeye devam edebilir."),
            s("Gizlilik tasarımın parçası", "TextGate parola ve diğer hassas alanları engeller, Gemini API anahtarınızı bu cihazda şifreler ve hangi uygulamalarda çalışacağını sizin kontrol etmenizi sağlar. Hazırsınız.")
        ),
        "ar" to c(
            "تخطي", "رجوع", "التالي", "ابدأ استخدام TextGate AI", "عرض البرنامج التعليمي مرة أخرى",
            s("مرحبًا بك في TextGate AI", "ترجم النص الذي تكتبه والرسائل الواردة والكلام. تعرض هذه الجولة السريعة أهم طرق استخدام TextGate AI."),
            s("ترجمة النص", "استخدم الترجمة للنص العادي. اختر اللغات واكتب أو أمْلِ النص، ثم انسخ النتيجة أو استمع إليها."),
            s("الترجمة أثناء الكتابة", "في تطبيقات المراسلة والشبكات الاجتماعية المسموح بها، أنهِ رسالتك بمشغّل مثل ?en أو ?de. يستبدله TextGate بالترجمة قبل الإرسال."),
            s("ترجمة الرسائل الواردة", "في التطبيقات المدعومة، اضغط مطولًا على رسالة أو تعليق. يعرض TextGate فقاعة ترجمة مؤقتة دون تغيير النص الأصلي."),
            s("المحادثة وLive", "استخدم المحادثة للحوار الصوتي المتبادل. يمكن لـ Live الاستمرار في الاستماع وترجمة الكلام من حولك عبر سماعة الرأس أو مكبر الصوت."),
            s("الخصوصية جزء من التصميم", "يحظر TextGate كلمات المرور والحقول الحساسة الأخرى، ويشفّر مفتاح Gemini API على هذا الجهاز، ويمنحك التحكم في التطبيقات التي يمكنه العمل فيها. أنت جاهز.")
        ),
        "fa" to c(
            "رد کردن", "بازگشت", "بعدی", "شروع استفاده از TextGate AI", "نمایش دوباره آموزش",
            s("به TextGate AI خوش آمدید", "متن تایپ‌شده، پیام‌های دریافتی و گفتار را ترجمه کنید. این راهنمای کوتاه روش‌های اصلی استفاده از TextGate AI را نشان می‌دهد."),
            s("ترجمه متن", "برای متن معمولی از بخش ترجمه استفاده کنید. زبان‌ها را انتخاب کنید، بنویسید یا دیکته کنید و سپس نتیجه را کپی یا پخش کنید."),
            s("ترجمه هنگام تایپ", "در پیام‌رسان‌ها و شبکه‌های اجتماعی مجاز، پیام را با محرکی مثل ?en یا ?de تمام کنید. TextGate پیش از ارسال آن را با ترجمه جایگزین می‌کند."),
            s("ترجمه پیام‌های دریافتی", "در برنامه‌های پشتیبانی‌شده، روی پیام یا نظر لمس طولانی کنید. TextGate بدون تغییر متن اصلی یک حباب ترجمه موقت نشان می‌دهد."),
            s("مکالمه و Live", "برای گفت‌وگوی شفاهی از مکالمه استفاده کنید. Live می‌تواند به شنیدن ادامه دهد و گفتار اطراف شما را از طریق هدفون یا بلندگو ترجمه کند."),
            s("حریم خصوصی در طراحی", "TextGate گذرواژه‌ها و دیگر فیلدهای حساس را مسدود می‌کند، کلید Gemini API را روی این دستگاه رمزگذاری می‌کند و کنترل برنامه‌های مجاز را به شما می‌دهد. آماده‌اید.")
        ),
        "iw" to c(
            "דלג", "חזרה", "הבא", "התחל להשתמש ב-TextGate AI", "הצג שוב את המדריך",
            s("ברוכים הבאים ל-TextGate AI", "תרגמו טקסט שאתם מקלידים, הודעות שקיבלתם ודיבור. הסיור הקצר הזה מציג את הדרכים העיקריות להשתמש ב-TextGate AI."),
            s("תרגום טקסט", "השתמשו בתרגום עבור טקסט רגיל. בחרו שפות, הקלידו או הכתיבו ואז העתיקו את התוצאה או האזינו לה."),
            s("תרגום בזמן ההקלדה", "באפליקציות הודעות ורשתות חברתיות מורשות, סיימו את ההודעה בטריגר כמו ?en או ?de. TextGate מחליף אותו בתרגום לפני השליחה."),
            s("תרגום הודעות שהתקבלו", "באפליקציות נתמכות, לחצו לחיצה ארוכה על הודעה או תגובה. TextGate מציג בועת תרגום זמנית בלי לשנות את המקור."),
            s("שיחה ו-Live", "השתמשו בשיחה לדיאלוג קולי. Live יכול להמשיך להאזין ולתרגם דיבור סביבכם דרך אוזניות או רמקול."),
            s("פרטיות מהתכנון", "TextGate חוסם סיסמאות ושדות רגישים אחרים, מצפין את מפתח Gemini API במכשיר הזה ומאפשר לכם לשלוט באילו אפליקציות הוא פועל. הכול מוכן.")
        ),
        "hi" to c(
            "छोड़ें", "पीछे", "आगे", "TextGate AI इस्तेमाल करना शुरू करें", "ट्यूटोरियल फिर दिखाएँ",
            s("TextGate AI में आपका स्वागत है", "टाइप किया हुआ टेक्स्ट, मिले हुए संदेश और आवाज़ का अनुवाद करें। यह छोटा टूर TextGate AI के मुख्य उपयोग दिखाता है।"),
            s("टेक्स्ट का अनुवाद", "सामान्य टेक्स्ट के लिए अनुवाद टैब इस्तेमाल करें। भाषाएँ चुनें, टाइप या डिक्टेट करें, फिर परिणाम कॉपी करें या सुनें।"),
            s("टाइप करते समय अनुवाद", "अनुमत मैसेजिंग और सोशल ऐप्स में संदेश के अंत में ?en या ?de जैसा ट्रिगर जोड़ें। भेजने से पहले TextGate इसे अनुवाद से बदल देता है।"),
            s("मिले हुए संदेशों का अनुवाद", "समर्थित ऐप्स में किसी संदेश या टिप्पणी को देर तक दबाएँ। TextGate मूल टेक्स्ट बदले बिना अस्थायी अनुवाद बबल दिखाता है।"),
            s("बातचीत और Live", "बोलकर आगे-पीछे बातचीत के लिए Conversation इस्तेमाल करें। Live आसपास की आवाज़ सुनता रह सकता है और हेडसेट या स्पीकर से अनुवाद चला सकता है।"),
            s("डिज़ाइन से ही निजी", "TextGate पासवर्ड और दूसरे संवेदनशील फ़ील्ड ब्लॉक करता है, इस डिवाइस पर आपकी Gemini API कुंजी एन्क्रिप्ट करता है और आपको तय करने देता है कि यह किन ऐप्स में चले। आप तैयार हैं।")
        ),
        "in" to c(
            "Lewati", "Kembali", "Berikutnya", "Mulai gunakan TextGate AI", "Tampilkan tutorial lagi",
            s("Selamat datang di TextGate AI", "Terjemahkan teks yang Anda ketik, pesan yang diterima, dan ucapan. Tur singkat ini menunjukkan cara utama menggunakan TextGate AI."),
            s("Terjemahkan teks", "Gunakan Terjemahkan untuk teks biasa. Pilih bahasa, ketik atau dikte, lalu salin hasilnya atau dengarkan."),
            s("Terjemahkan saat mengetik", "Di aplikasi pesan dan media sosial yang diizinkan, akhiri pesan dengan pemicu seperti ?en atau ?de. TextGate menggantinya dengan terjemahan sebelum dikirim."),
            s("Terjemahkan pesan masuk", "Di aplikasi yang didukung, tekan lama pesan atau komentar. TextGate menampilkan gelembung terjemahan sementara tanpa mengubah teks asli."),
            s("Percakapan dan Live", "Gunakan Percakapan untuk dialog suara dua arah. Live dapat terus mendengarkan dan menerjemahkan ucapan di sekitar Anda melalui headset atau speaker."),
            s("Privasi sejak awal", "TextGate memblokir kata sandi dan bidang sensitif lainnya, mengenkripsi kunci Gemini API di perangkat ini, dan memberi Anda kendali atas aplikasi yang boleh menggunakannya. Anda siap.")
        ),
        "ms" to c(
            "Langkau", "Kembali", "Seterusnya", "Mula menggunakan TextGate AI", "Tunjukkan tutorial lagi",
            s("Selamat datang ke TextGate AI", "Terjemahkan teks yang ditaip, mesej yang diterima dan pertuturan. Lawatan ringkas ini menunjukkan cara utama menggunakan TextGate AI."),
            s("Terjemah teks", "Gunakan Terjemah untuk teks biasa. Pilih bahasa, taip atau imlak, kemudian salin hasilnya atau dengarkannya."),
            s("Terjemah semasa menaip", "Dalam aplikasi mesej dan sosial yang dibenarkan, akhiri mesej dengan pencetus seperti ?en atau ?de. TextGate menggantikannya dengan terjemahan sebelum dihantar."),
            s("Terjemah mesej diterima", "Dalam aplikasi yang disokong, tekan lama mesej atau komen. TextGate memaparkan gelembung terjemahan sementara tanpa mengubah teks asal."),
            s("Perbualan dan Live", "Gunakan Perbualan untuk dialog lisan dua hala. Live boleh terus mendengar dan menterjemah pertuturan di sekeliling anda melalui set kepala atau pembesar suara."),
            s("Privasi sejak reka bentuk", "TextGate menyekat kata laluan dan medan sensitif lain, menyulitkan kunci Gemini API pada peranti ini dan membolehkan anda mengawal aplikasi yang boleh menggunakannya. Anda sudah bersedia.")
        ),
        "vi" to c(
            "Bỏ qua", "Quay lại", "Tiếp", "Bắt đầu dùng TextGate AI", "Hiển thị lại hướng dẫn",
            s("Chào mừng đến với TextGate AI", "Dịch văn bản bạn nhập, tin nhắn nhận được và lời nói. Hướng dẫn nhanh này giới thiệu những cách chính để sử dụng TextGate AI."),
            s("Dịch văn bản", "Dùng Dịch cho văn bản thông thường. Chọn ngôn ngữ, nhập hoặc đọc chính tả, sau đó sao chép kết quả hoặc nghe lại."),
            s("Dịch khi đang gõ", "Trong các ứng dụng nhắn tin và mạng xã hội được cho phép, kết thúc tin nhắn bằng trình kích hoạt như ?en hoặc ?de. TextGate sẽ thay nó bằng bản dịch trước khi gửi."),
            s("Dịch tin nhắn nhận được", "Trong ứng dụng được hỗ trợ, nhấn giữ một tin nhắn hoặc bình luận. TextGate hiển thị bong bóng dịch tạm thời mà không thay đổi nội dung gốc."),
            s("Hội thoại và Live", "Dùng Hội thoại cho đối thoại bằng giọng nói. Live có thể tiếp tục lắng nghe và dịch lời nói xung quanh qua tai nghe hoặc loa."),
            s("Riêng tư ngay từ thiết kế", "TextGate chặn mật khẩu và các trường nhạy cảm khác, mã hóa khóa Gemini API trên thiết bị này và cho phép bạn kiểm soát ứng dụng nào được dùng TextGate. Bạn đã sẵn sàng.")
        ),
        "th" to c(
            "ข้าม", "ย้อนกลับ", "ถัดไป", "เริ่มใช้ TextGate AI", "แสดงบทแนะนำอีกครั้ง",
            s("ยินดีต้อนรับสู่ TextGate AI", "แปลข้อความที่พิมพ์ ข้อความที่ได้รับ และคำพูด ทัวร์สั้น ๆ นี้จะแนะนำวิธีหลักในการใช้ TextGate AI"),
            s("แปลข้อความ", "ใช้เมนูแปลสำหรับข้อความทั่วไป เลือกภาษา พิมพ์หรือพูดตามคำบอก แล้วคัดลอกผลลัพธ์หรือฟังเสียงได้"),
            s("แปลขณะพิมพ์", "ในแอปแชตและโซเชียลที่อนุญาต ให้จบข้อความด้วยตัวเรียกอย่าง ?en หรือ ?de แล้ว TextGate จะแทนที่ด้วยคำแปลก่อนส่ง"),
            s("แปลข้อความที่ได้รับ", "ในแอปที่รองรับ ให้กดข้อความหรือความคิดเห็นค้างไว้ TextGate จะแสดงบับเบิลคำแปลชั่วคราวโดยไม่แก้ไขต้นฉบับ"),
            s("การสนทนาและ Live", "ใช้การสนทนาสำหรับบทสนทนาด้วยเสียงแบบโต้ตอบ ส่วน Live สามารถฟังต่อเนื่องและแปลเสียงรอบตัวผ่านหูฟังหรือลำโพง"),
            s("ความเป็นส่วนตัวตั้งแต่การออกแบบ", "TextGate บล็อกรหัสผ่านและช่องข้อมูลละเอียดอ่อนอื่น ๆ เข้ารหัสคีย์ Gemini API บนอุปกรณ์นี้ และให้คุณควบคุมว่าแอปใดใช้ TextGate ได้ พร้อมใช้งานแล้ว")
        ),
        "ja" to c(
            "スキップ", "戻る", "次へ", "TextGate AI を使い始める", "チュートリアルをもう一度表示",
            s("TextGate AI へようこそ", "入力した文章、受信メッセージ、音声を翻訳できます。この短いツアーで TextGate AI の主な使い方を紹介します。"),
            s("テキストを翻訳", "通常の文章は「翻訳」を使います。言語を選び、入力または音声入力して、結果をコピーしたり読み上げたりできます。"),
            s("入力しながら翻訳", "許可したメッセージ・SNS アプリでは、文末に ?en や ?de などのトリガーを付けます。送信前に TextGate が翻訳文へ置き換えます。"),
            s("受信メッセージを翻訳", "対応アプリでメッセージやコメントを長押しすると、元の文章を変更せず一時的な翻訳バブルを表示できます。"),
            s("会話と Live", "会話モードは音声でのやり取りに使います。Live は周囲の音声を聞き続け、ヘッドセットやスピーカーへ翻訳音声を出せます。"),
            s("プライバシーを重視", "TextGate はパスワードなどの機密入力欄をブロックし、Gemini API キーをこの端末で暗号化します。使用を許可するアプリも自分で管理できます。準備完了です。")
        ),
        "ko" to c(
            "건너뛰기", "뒤로", "다음", "TextGate AI 시작하기", "튜토리얼 다시 보기",
            s("TextGate AI에 오신 것을 환영합니다", "입력한 텍스트, 받은 메시지, 음성을 번역할 수 있습니다. 이 짧은 안내에서 TextGate AI의 주요 사용 방법을 소개합니다."),
            s("텍스트 번역", "일반 텍스트는 번역 탭을 사용하세요. 언어를 선택하고 입력하거나 받아쓴 뒤 결과를 복사하거나 소리로 들을 수 있습니다."),
            s("입력하면서 번역", "허용된 메신저와 소셜 앱에서 메시지 끝에 ?en 또는 ?de 같은 트리거를 붙이세요. 보내기 전에 TextGate가 번역문으로 바꿉니다."),
            s("받은 메시지 번역", "지원되는 앱에서 메시지나 댓글을 길게 누르세요. TextGate가 원문을 바꾸지 않고 임시 번역 버블을 표시합니다."),
            s("대화와 Live", "대화는 음성으로 주고받는 대화에 사용합니다. Live는 계속 듣고 주변의 말을 헤드셋이나 스피커로 번역해 들려줄 수 있습니다."),
            s("처음부터 개인정보 보호", "TextGate는 비밀번호와 기타 민감한 입력란을 차단하고 이 기기에서 Gemini API 키를 암호화하며, 어떤 앱에서 사용할지 직접 제어할 수 있게 합니다. 준비되었습니다.")
        ),
        "zh" to c(
            "略過", "返回", "下一步", "開始使用 TextGate AI", "再次顯示教學",
            s("歡迎使用 TextGate AI", "翻譯你輸入的文字、收到的訊息與語音。這個快速教學會介紹 TextGate AI 的主要使用方式。"),
            s("翻譯文字", "一般文字可使用「翻譯」。選擇語言後輸入或語音輸入內容，再複製結果或播放翻譯語音。"),
            s("輸入時即時翻譯", "在允許的通訊與社群應用程式中，於訊息結尾加入 ?en 或 ?de 等觸發碼。TextGate 會在送出前將它替換成翻譯內容。"),
            s("翻譯收到的訊息", "在支援的應用程式中長按訊息或留言。TextGate 會顯示暫時的翻譯氣泡，不會修改原文。"),
            s("對話與 Live", "「對話」適合口語雙向交流。Live 可持續聆聽並翻譯周圍語音，透過耳機或喇叭播放。"),
            s("隱私融入設計", "TextGate 會封鎖密碼與其他敏感欄位，在此裝置上加密 Gemini API 金鑰，並讓你控制可使用 TextGate 的應用程式。你已準備完成。")
        ),
        "zh-rCN" to c(
            "跳过", "返回", "下一步", "开始使用 TextGate AI", "再次显示教程",
            s("欢迎使用 TextGate AI", "翻译你输入的文字、收到的消息和语音。这个快速教程会介绍 TextGate AI 的主要使用方式。"),
            s("翻译文本", "普通文本可使用“翻译”。选择语言后输入或语音输入内容，然后复制结果或播放翻译语音。"),
            s("输入时翻译", "在允许的聊天和社交应用中，在消息末尾加入 ?en 或 ?de 等触发码。TextGate 会在发送前将它替换为翻译内容。"),
            s("翻译收到的消息", "在支持的应用中长按消息或评论。TextGate 会显示临时翻译气泡，不会修改原文。"),
            s("对话和 Live", "“对话”适合语音双向交流。Live 可以持续监听并翻译周围的语音，通过耳机或扬声器播放。"),
            s("隐私融入设计", "TextGate 会屏蔽密码和其他敏感字段，在此设备上加密 Gemini API 密钥，并让你控制哪些应用可以使用 TextGate。你已准备就绪。")
        ),
        "ca" to c(
            "Omet", "Enrere", "Següent", "Comença a utilitzar TextGate AI", "Torna a mostrar el tutorial",
            s("Benvingut a TextGate AI", "Tradueix text escrit, missatges rebuts i veu. Aquest recorregut ràpid mostra les principals maneres d’utilitzar TextGate AI."),
            s("Tradueix text", "Utilitza Tradueix per al text normal. Tria els idiomes, escriu o dicta i després copia el resultat o escolta’l."),
            s("Tradueix mentre escrius", "A les aplicacions de missatgeria i xarxes socials permeses, acaba el missatge amb un activador com ?en o ?de. TextGate el substitueix per la traducció abans d’enviar."),
            s("Tradueix missatges rebuts", "A les aplicacions compatibles, mantén premut un missatge o comentari. TextGate mostra una bombolla temporal amb la traducció sense modificar l’original."),
            s("Conversa i Live", "Utilitza Conversa per al diàleg parlat. Live pot continuar escoltant i traduint la parla del teu voltant pels auriculars o l’altaveu."),
            s("Privadesa des del disseny", "TextGate bloqueja contrasenyes i altres camps sensibles, xifra la clau API de Gemini en aquest dispositiu i et deixa controlar en quines aplicacions funciona. Ja està tot a punt.")
        ),
        "et" to c(
            "Jäta vahele", "Tagasi", "Edasi", "Alusta TextGate AI kasutamist", "Näita õpetust uuesti",
            s("Tere tulemast TextGate AI-sse", "Tõlgi sisestatud teksti, saadud sõnumeid ja kõnet. See lühike juhend näitab TextGate AI peamisi kasutusviise."),
            s("Tõlgi teksti", "Tavalise teksti jaoks kasuta Tõlget. Vali keeled, kirjuta või dikteeri ning kopeeri tulemus või kuula seda."),
            s("Tõlgi kirjutamise ajal", "Lubatud sõnumi- ja sotsiaalrakendustes lõpeta sõnum käivitajaga, näiteks ?en või ?de. TextGate asendab selle enne saatmist tõlkega."),
            s("Tõlgi saadud sõnumeid", "Toetatud rakendustes vajuta sõnumit või kommentaari pikalt. TextGate kuvab ajutise tõlkemulli ega muuda algteksti."),
            s("Vestlus ja Live", "Kasuta Vestlust suuliseks dialoogiks. Live võib jätkata kuulamist ja tõlkida ümbritsevat kõnet peakomplekti või kõlari kaudu."),
            s("Privaatsus on sisse ehitatud", "TextGate blokeerib paroolid ja muud tundlikud väljad, krüpteerib Gemini API võtme selles seadmes ning laseb sul valida, millistes rakendustes see töötab. Kõik on valmis.")
        ),
        "lv" to c(
            "Izlaist", "Atpakaļ", "Tālāk", "Sākt lietot TextGate AI", "Rādīt pamācību vēlreiz",
            s("Laipni lūdzam TextGate AI", "Tulkojiet rakstītu tekstu, saņemtas ziņas un runu. Šī īsā pamācība parāda galvenos TextGate AI lietošanas veidus."),
            s("Tulkot tekstu", "Parastam tekstam izmantojiet Tulkot. Izvēlieties valodas, rakstiet vai diktējiet un pēc tam kopējiet rezultātu vai klausieties to."),
            s("Tulkot rakstīšanas laikā", "Atļautās ziņapmaiņas un sociālajās lietotnēs pabeidziet ziņu ar aktivizētāju, piemēram, ?en vai ?de. TextGate pirms nosūtīšanas to aizstāj ar tulkojumu."),
            s("Tulkot saņemtās ziņas", "Atbalstītās lietotnēs turiet nospiestu ziņu vai komentāru. TextGate parāda pagaidu tulkojuma burbuli, nemainot oriģinālu."),
            s("Saruna un Live", "Izmantojiet Sarunu mutiskam dialogam. Live var turpināt klausīties un tulkot apkārtējo runu austiņās vai skaļrunī."),
            s("Privātums jau pēc dizaina", "TextGate bloķē paroles un citus sensitīvus laukus, šifrē Gemini API atslēgu šajā ierīcē un ļauj jums noteikt, kurās lietotnēs tas darbojas. Viss ir gatavs.")
        ),
        "lt" to c(
            "Praleisti", "Atgal", "Toliau", "Pradėti naudoti TextGate AI", "Rodyti mokymą dar kartą",
            s("Sveiki atvykę į TextGate AI", "Verskite įvedamą tekstą, gautas žinutes ir kalbą. Ši trumpa apžvalga parodo pagrindinius TextGate AI naudojimo būdus."),
            s("Versti tekstą", "Įprastam tekstui naudokite Vertimą. Pasirinkite kalbas, rašykite arba diktuokite, tada nukopijuokite rezultatą arba jo paklausykite."),
            s("Versti rašant", "Leidžiamose žinučių ir socialinėse programėlėse užbaikite žinutę aktyvikliu, pavyzdžiui, ?en arba ?de. TextGate prieš siunčiant pakeis jį vertimu."),
            s("Versti gautas žinutes", "Palaikomose programėlėse ilgai paspauskite žinutę ar komentarą. TextGate parodys laikiną vertimo burbulą nekeisdamas originalo."),
            s("Pokalbis ir Live", "Pokalbį naudokite žodiniam dialogui. Live gali toliau klausytis ir versti aplink girdimą kalbą per ausines arba garsiakalbį."),
            s("Privatumas pagal dizainą", "TextGate blokuoja slaptažodžius ir kitus jautrius laukus, šiame įrenginyje užšifruoja Gemini API raktą ir leidžia jums valdyti, kuriose programėlėse jis veikia. Viskas paruošta.")
        )
    )
}
