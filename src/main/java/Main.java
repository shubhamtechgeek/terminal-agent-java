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
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Main {

    static void main(String[] args) throws IOException {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

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



        //initialize conversation
        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                .content(prompt)
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


    static String toolCall(ChatCompletionMessageToolCall toolCall) throws IOException {

            String functionName = toolCall.function().name();

            if ("Read".equals(functionName)) {
                // 1. Parse the JSON arguments string
                String argumentsJson = toolCall.function().arguments();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode argsNode = mapper.readTree(argumentsJson);
                String filePath = argsNode.get("file_path").asText();

                // 2. Read the file contents and print raw output to stdout
                return Files.readString(Path.of(filePath));

            } else if ("Write".equals(functionName)) {

                //1. Parse the JSON arguments string
                String argumentsJson = toolCall.function().arguments();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode argsNode = mapper.readTree(argumentsJson);
                String filePath = argsNode.get("file_path").asText();
                String content = argsNode.get("content").asText();
                Path path = Path.of(filePath);
                if(!Files.exists(path)){
                    Files.createFile(path);
                }
                return String.valueOf(Files.writeString(path, content));

            } else if ("Bash".equals(functionName)) {

                // 1. Parse the JSON arguments string
                String argumentsJson = toolCall.function().arguments();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode argsNode = mapper.readTree(argumentsJson);
                String command = argsNode.get("command").asText();

                ProcessBuilder processBuilder = new ProcessBuilder(command);

                if (processBuilder.redirectErrorStream()){

                    return processBuilder.redirectError().toString();
                }else{

                    return processBuilder.redirectOutput().toString();
                }

            }

        throw new IllegalArgumentException("Unknown tool: " + functionName);
    }

}
