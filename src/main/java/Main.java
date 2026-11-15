import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class Main {

    static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String userPrompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        FunctionParameters readParameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "file_path", Map.of(
                        "type", "string",
                        "description", "The path to the file to read"
                    )
                )))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                .build();


        FunctionParameters writeParameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "file_path", Map.of(
                        "type", "string",
                        "description", "The path to the file to write to"
                        ),
                    "content", Map.of(
                            "type", "string",
                            "description", "The content to write to the file")
                        )
                ))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "content")))
                .build();

        FunctionParameters bashParameters = FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "command", Map.of(
                        "type", "string",
                        "description", "the command to execute"
                    )
                )
            ))
            .putAdditionalProperty("required", JsonValue.from(List.of("command")))
            .build();

        ChatCompletionTool readTool =
            ChatCompletionTool.builder()
                .type(JsonValue.from("function"))
                .function(
                    FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(readParameters)
                        .build())
                .build();


        ChatCompletionTool writeTool =
            ChatCompletionTool.builder()
                .type(JsonValue.from("function"))
                .function(
                    FunctionDefinition.builder()
                        .name("Write")
                        .description("Write Content to a file")
                        .parameters(writeParameters)
                        .build())
                .build();


        ChatCompletionTool bashTool =
            ChatCompletionTool.builder()
                .type(JsonValue.from("function"))
                .function(
                    FunctionDefinition.builder()
                        .name("Bash")
                        .description("Execute a shell command")
                        .parameters(bashParameters)
                        .build())
                .build();


        //extract system prompt from .claude/skills/ folder
        Path projectRoot = Paths.get(".");
        Path skillsDir = projectRoot.resolve(".claude/skills");
        String systemPrompt = buildSystemPrompt(skillsDir);

        if (userPrompt.startsWith("/")) {
            String trimmedPrompt = userPrompt.trim();
            String command = trimmedPrompt.substring(1).split("\\s+")[0];
            String argsPart = trimmedPrompt.substring(1 + command.length()).trim();

            for (Skill skill : loadSkills(skillsDir)) {
                if (skill.name.equalsIgnoreCase(command)
                    || skill.path().toString().equalsIgnoreCase(command)) {
                    if (argsPart.isEmpty()) {
                        userPrompt = skill.body;
                    } else {
                        userPrompt = skill.body + "\n\n" + argsPart;
                    }
                    break;
                }
            }
        }

        //initialize conversation
        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofSystem(
            ChatCompletionSystemMessageParam.builder()
                .content(systemPrompt)
                .build()
        ));
        messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                .content(userPrompt)
                .build()
        ));

        List<ChatCompletionTool> tools = List.of(readTool, writeTool, bashTool);


        //implement agent loop
        while (true){

            ChatCompletion response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model("anthropic/claude-haiku-4.5")
                    .messages(messages)
                    .tools(tools)
                    .build()
            );

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            ChatCompletionMessage assistantMessage = response.choices().getFirst().message();

            //maintain conversation history
            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage.toParam()));

            Optional<List<ChatCompletionMessageToolCall>> toolCallsOpt = assistantMessage.toolCalls();

            //exit if not tool calls.
            if(toolCallsOpt.isEmpty() || toolCallsOpt.get().isEmpty()){
                System.out.print(assistantMessage.content().orElse(""));
                break;
            }

            //execute all tool calls:
            for(ChatCompletionMessageToolCall toolCall : toolCallsOpt.get()){

                String result = toolCall(toolCall);

                messages.add(ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                        .toolCallId(toolCall.id())
                        .content(result)
                        .build()
                ));
            }
        }
    }

    public record Skill(String name, String description, String body, Path path) {}

    private static String buildSystemPrompt(Path skillsDir) throws IOException {

        Path projectRoot = Paths.get(".");

        List<Skill> skills = loadSkills(skillsDir);

        StringBuilder prompt = new StringBuilder();

        prompt.append("You have access to the following skills:\n\n");

        if (skills.isEmpty()){
            return prompt.toString();
        }

        for (Skill skill : skills) {
            prompt.append(String.format("- **%s**: %s (Path: `%s`)\n",
                skill.name(),
                skill.description(),
                projectRoot.relativize(skill.path())));
        }

        return prompt.toString();
    }

    private static List<Skill> loadSkills(Path skillsDir) throws IOException {


        List<Skill> Skill = new ArrayList<>();

        if(!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)){
            return Skill;
        }

        try(Stream<Path> entries = Files.list(skillsDir)) {

            for(Path entry : entries.filter(Files::isDirectory).toList()){
                Path skillFile = entry.resolve("SKILL.md");
                if(Files.exists(skillFile)){
                    Skill metadata = parseFrontmatter(skillFile);
                    if (metadata != null) {
                        Skill.add(metadata);
                    }
                }
            }

        }

        return Skill;

    }

    private static Skill parseFrontmatter(Path skillFile) throws IOException {

        List<String> lines = Files.readAllLines(skillFile);
        if (lines.isEmpty() || !lines.getFirst().trim().equals("---")) {
            return null;
        }

        String name = null;
        String description = null;
        StringBuilder body = null;

        // Start at line 1 (after opening ---) and stop at closing ---
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.equals("---")) {
                break; // Stop reading before the body (Level 2)
            }
            if (line.startsWith("name:")) {
                name = line.substring("name:".length()).trim();
            } else if (line.startsWith("description:")) {
                description = line.substring("description:".length()).trim();
            }else{
                while(i < lines.size()){
                    body.append(line.trim());
                    i++;
                }
            }



        }

        if (name != null && description != null && body != null) {
            return new Skill(name, description, body.toString(), skillFile);
        }
        return null;
    }


    static String toolCall(ChatCompletionMessageToolCall toolCall)
        throws IOException, InterruptedException {

            String functionName = toolCall.function().name();

        switch (functionName) {
            case "Read" -> {
                // 1. Parse the JSON arguments string
                String argumentsJson = toolCall.function().arguments();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode argsNode = mapper.readTree(argumentsJson);
                String filePath = argsNode.get("file_path").asText();

                // 2. Read the file contents and print raw output to stdout
                return Files.readString(Path.of(filePath));

            }
            case "Write" -> {

                //1. Parse the JSON arguments string
                String argumentsJson = toolCall.function().arguments();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode argsNode = mapper.readTree(argumentsJson);
                String filePath = argsNode.get("file_path").asText();
                String content = argsNode.get("content").asText();
                Path path = Path.of(filePath);
                if (!Files.exists(path)) {
                    Files.createFile(path);
                }
                return String.valueOf(Files.writeString(path, content));

            }
            case "Bash" -> {

                // 1. Parse the JSON arguments string
                String argumentsJson = toolCall.function().arguments();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode argsNode = mapper.readTree(argumentsJson);
                String command = argsNode.get("command").asText();

                //we build a process and enter the command
                // sh because we have to start a shell
                ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", command);

                //no need to capture error or output separately, we can capture it together.
                processBuilder.redirectErrorStream(true);

                try {
                    Process process = processBuilder.start();
                    String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    int exitCode = process.waitFor();

                    if (exitCode != 0 && output.isEmpty()) {
                        return "Command failed with exit code " + exitCode;
                    }
                    return output;
                } catch (Exception e) {
                    return "Error executing command: " + e.getMessage();
                }

            }
        }

        throw new IllegalArgumentException("Unknown tool: " + functionName);
    }

}
