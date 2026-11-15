import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.beta.threads.messages.Message;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        FunctionParameters parameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "file_path", Map.of(
                        "type", "string",
                        "description", "The path to the file to read"
                    )
                )))
                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                .build();

        ChatCompletionTool tool =
            ChatCompletionTool.builder()
                .type(JsonValue.from("function"))
                .function(
                    FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(parameters)
                        .build())
                .build();

        //initialize conversation
        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                .content(prompt)
                .build()
        ));


        //implement agent loop
        while (true){

            ChatCompletion response = client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model("anthropic/claude-haiku-4.5")
                    .addUserMessage(prompt)
                    .addTool(tool)
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

            }

            throw new IllegalArgumentException("Unknown tool: " + functionName);
    }

}
