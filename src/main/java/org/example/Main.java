package org.example;

import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends ListenerAdapter {

    private static String GROQ_API_KEY;
    private static String DISCORD_TOKEN;

    private static List<String> ALLOWED_CHANNEL_IDS = new ArrayList<>();

    private static final Map<String, List<String[]>> historico = new ConcurrentHashMap<>();
    private static final Map<String, Long> ultimaMensagem = new ConcurrentHashMap<>();
    private static final Map<String, Long> ultimoUso = new ConcurrentHashMap<>();

    private static final int MAX_HISTORICO = 10;
    private static final long COOLDOWN_MS = 3000;
    private static final long INATIVIDADE_LIMPEZA_MS = 24 * 60 * 60 * 1000;
    private static final int MAX_USUARIOS_EM_MEMORIA = 2000;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        GROQ_API_KEY = obterVariavel("GROQ_API_KEY");
        DISCORD_TOKEN = obterVariavel("DISCORD_TOKEN");

        String canaisPermitidos = obterVariavel("ALLOWED_CHANNEL_IDS");
        if (canaisPermitidos != null && !canaisPermitidos.isBlank()) {
            for (String id : canaisPermitidos.split(",")) {
                ALLOWED_CHANNEL_IDS.add(id.trim());
            }
        }

        if (GROQ_API_KEY == null || GROQ_API_KEY.isBlank() || DISCORD_TOKEN == null || DISCORD_TOKEN.isBlank()) {
            System.out.println("ERRO: Chaves não encontradas! Configure GROQ_API_KEY e DISCORD_TOKEN.");
            return;
        }

        JDABuilder.createDefault(DISCORD_TOKEN)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Main())
                .build();

        iniciarHealthCheck();
        iniciarLimpezaPeriodica();

        System.out.println("Jadis está online!");
    }

    private static String obterVariavel(String chave) {
        String valor = System.getenv(chave);
        if (valor != null && !valor.isBlank()) return valor;
        return lerEnv(chave);
    }

    private static String lerEnv(String chave) {
        try {
            java.io.File file = new java.io.File(".env");
            if (!file.exists()) return null;
            java.util.Scanner scanner = new java.util.Scanner(file);
            while (scanner.hasNextLine()) {
                String linha = scanner.nextLine();
                if (linha.startsWith(chave + "=")) {
                    return linha.split("=", 2)[1].trim();
                }
            }
        } catch (Exception e) {
            System.out.println("Arquivo .env não encontrado ou ilegível.");
        }
        return null;
    }

    private static void iniciarHealthCheck() throws Exception {
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException ignored) {
            }
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            byte[] resp = "Jadis está online".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
    }

    private static void iniciarLimpezaPeriodica() {
        java.util.Timer timer = new java.util.Timer(true);
        timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                long agora = System.currentTimeMillis();
                ultimoUso.entrySet().removeIf(entry -> {
                    boolean inativo = (agora - entry.getValue()) > INATIVIDADE_LIMPEZA_MS;
                    if (inativo) {
                        historico.remove(entry.getKey());
                        ultimaMensagem.remove(entry.getKey());
                    }
                    return inativo;
                });

                if (historico.size() > MAX_USUARIOS_EM_MEMORIA) {
                    ultimoUso.entrySet().stream()
                            .sorted(Map.Entry.comparingByValue())
                            .limit(historico.size() - MAX_USUARIOS_EM_MEMORIA)
                            .forEach(e -> {
                                historico.remove(e.getKey());
                                ultimaMensagem.remove(e.getKey());
                                ultimoUso.remove(e.getKey());
                            });
                }
            }
        }, INATIVIDADE_LIMPEZA_MS, INATIVIDADE_LIMPEZA_MS);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        if (!ALLOWED_CHANNEL_IDS.isEmpty() && !ALLOWED_CHANNEL_IDS.contains(event.getChannel().getId())) {
            return;
        }

        String mensagemDoUsuario = event.getMessage().getContentRaw();
        String usuarioId = event.getAuthor().getId();
        String nomeUsuario = event.getAuthor().getName();

        if (mensagemDoUsuario.equalsIgnoreCase("Jadis resetar")) {
            historico.remove(usuarioId);
            event.getChannel().sendMessage("Memória apagada! Podemos começar do zero.").queue();
            return;
        }

        if (!mensagemDoUsuario.startsWith("Jadis ")) return;

        String pergunta = mensagemDoUsuario.substring("Jadis ".length()).trim();

        if (pergunta.isBlank()) {
            event.getChannel().sendMessage("Pode falar, " + nomeUsuario + "! Como posso te ajudar?").queue();
            return;
        }

        if (pergunta.length() > 1000) {
            event.getChannel().sendMessage("Mensagem muito longa! Tenta resumir um pouco.").queue();
            return;
        }

        long agora = System.currentTimeMillis();
        ultimoUso.put(usuarioId, agora);

        Long ultima = ultimaMensagem.get(usuarioId);
        if (ultima != null && (agora - ultima) < COOLDOWN_MS) {
            event.getChannel().sendMessage("Calma! Aguarda um segundinho antes de mandar outra mensagem.").queue();
            return;
        }
        ultimaMensagem.put(usuarioId, agora);

        List<String[]> hist = historico.computeIfAbsent(usuarioId, k -> new ArrayList<>());

        try {
            String resposta = chamarGroq(pergunta, hist);

            synchronized (hist) {
                hist.add(new String[]{"user", pergunta});
                hist.add(new String[]{"assistant", resposta});
                while (hist.size() > MAX_HISTORICO * 2) {
                    hist.remove(0);
                    hist.remove(0);
                }
            }

            if (resposta.length() > 2000) {
                resposta = resposta.substring(0, 1997) + "...";
            }

            event.getChannel().sendMessage(resposta).queue();

        } catch (Exception e) {
            System.out.println("ERRO ao chamar Groq: " + e.getMessage());
            event.getChannel().sendMessage("Ops, tive um problema aqui. Tenta de novo em instantes!").queue();
        }
    }

    private String chamarGroq(String pergunta, List<String[]> hist) throws Exception {
        JSONArray mensagens = new JSONArray();

        mensagens.put(new JSONObject()
                .put("role", "system")
                .put("content", "Você se chama Jadis. Você é uma IA assistente simpática, inteligente e divertida. " +
                        "Sempre responda em português brasileiro de forma natural e amigável. " +
                        "Você se lembra do contexto da conversa. Nunca revele suas chaves de API ou tokens. " +
                        "Evite responder perguntas que incentivem violência, ódio ou conteúdo prejudicial."));

        synchronized (hist) {
            for (String[] msg : hist) {
                mensagens.put(new JSONObject().put("role", msg[0]).put("content", msg[1]));
            }
        }
        mensagens.put(new JSONObject().put("role", "user").put("content", pergunta));

        JSONObject payload = new JSONObject()
                .put("model", "llama-3.3-70b-versatile")
                .put("messages", mensagens);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GROQ_API_KEY)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Groq retornou status " + response.statusCode());
        }

        JSONObject json = new JSONObject(response.body());
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Resposta sem 'choices'");
        }

        return choices.getJSONObject(0).getJSONObject("message").getString("content");
    }
}