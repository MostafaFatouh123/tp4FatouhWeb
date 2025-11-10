package ma.emsi.fatouh.tp4webfatouh.web;

import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Named
@SessionScoped
public class ChatBean implements Serializable {

    @Inject
    RagService ragService;

    private boolean useWeb = false;
    private boolean guardNoRag = true;
    private boolean multiPdf = true;
    private String userInput;

    private List<String> history = new ArrayList<>();

    public void send() {
        if (userInput == null || userInput.isBlank()) return;

        history.add("Vous : " + userInput);

        var assistant = ragService.buildAssistant(useWeb, guardNoRag, multiPdf);
        String answer = assistant.chat(userInput);

        history.add("Assistant : " + answer);
        userInput = "";
    }

    public boolean isUseWeb() { return useWeb; }
    public void setUseWeb(boolean useWeb) { this.useWeb = useWeb; }
    public boolean isGuardNoRag() { return guardNoRag; }
    public void setGuardNoRag(boolean guardNoRag) { this.guardNoRag = guardNoRag; }
    public boolean isMultiPdf() { return multiPdf; }
    public void setMultiPdf(boolean multiPdf) { this.multiPdf = multiPdf; }
    public String getUserInput() { return userInput; }
    public void setUserInput(String userInput) { this.userInput = userInput; }
    public List<String> getHistory() { return history; }
}

