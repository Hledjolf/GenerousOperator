package generousoperator.hytale;

import commanddeck.api.CheckResult;
import commanddeck.api.CommandDeckApi;
import commanddeck.api.CommandOutcome;
import commanddeck.api.Deck;
import commanddeck.api.MacroInfo;
import commanddeck.api.PreviewLine;
import commanddeck.api.Presser;
import commanddeck.api.RunItem;
import commanddeck.api.RunListener;
import commanddeck.api.RunRequest;
import commanddeck.api.RunResult;
import commanddeck.api.StartResult;
import commanddeck.api.StepEvent;
import commanddeck.api.Target;
import generousoperator.api.Person;
import generousoperator.give.GiftService;
import generousoperator.give.Ports;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * CommandDeck 1.1 as the core's runner. A gift given as a person is one CommandDeck run, pressed by the giver
 * with {@code {target}} set to the recipient, so CommandDeck's rules (the giver's permissions, confirmation,
 * escaping, {@code commanddeck.run_others}) all apply. The deck is looked up on each call, so CommandDeck can
 * load after Generous Operator.
 */
final class DeckRunner implements Ports.Runner {
    static final String CATEGORY = "Gifts";
    private static final int MAX_LABEL = 120;

    private static Deck deck() {
        Deck d = CommandDeckApi.get();
        return d != null && atLeast11(d.apiVersion()) ? d : null;
    }

    static boolean atLeast11(String version) {
        if (version == null) return false;
        String[] p = version.split("\\.");
        try {
            int major = Integer.parseInt(p[0]), minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return major == 1 && minor >= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public boolean available() {
        return deck() != null;
    }

    /** Asked as the console, which holds every permission, so the list doesn't depend on who asks. */
    @Override
    public List<Preset> giftPresets() {
        Deck d = deck();
        List<Preset> out = new ArrayList<>();
        if (d == null) return out;
        Person console = HytalePlayers.console();
        for (MacroInfo m : d.macros(new Presser(console.id(), console.name()), CATEGORY)) {
            // The console's own saved macros aren't the server's gifts.
            if (m.origin() == MacroInfo.Origin.PLAYER) continue;
            out.add(new Preset(m.id(), m.name(), m.description(), m.mod()));
        }
        return out;
    }

    @Override
    public Check check(Person giver, Person recipient, Plan plan, String source) {
        Deck d = deck();
        if (d == null) return new Check(false, "no_commanddeck", "Giving as a person needs CommandDeck 1.1.", null, List.of(), false, null, null);
        CheckResult c = d.check(presser(giver), request(recipient, plan, source));
        List<String> preview = new ArrayList<>();
        if (c.preview() != null) for (PreviewLine l : c.preview()) preview.add(l.line());
        if (!c.ok()) {
            return new Check(false, c.refusal().code(), c.refusal().message(), c.refusal().detail(), preview, false, null, null);
        }
        return new Check(true, null, null, null, preview, c.needsConfirmation(), c.confirmPrompt(), c.confirmToken());
    }

    @Override
    public Started start(Person giver, Person recipient, Plan plan, String source, String token, Consumer<Ran> done) {
        Deck d = deck();
        if (d == null) return new Started(false, "no_commanddeck", "Giving as a person needs CommandDeck 1.1.", null);
        StartResult s = d.start(presser(giver), request(recipient, plan, source), new RunListener() {
            @Override
            public void onStep(StepEvent step) {
            }

            @Override
            public void onFinish(RunResult r) {
                List<String> sent = new ArrayList<>();
                for (StepEvent e : r.steps()) {
                    if (e.outcome() != CommandOutcome.SENT) continue;
                    // A plan of lines is one step per line, in order (steps count from 1); a preset's lines are its own.
                    int k = e.stepIndex() - 1;
                    sent.add(plan.lines() != null && k >= 0 && k < plan.lines().size() ? plan.lines().get(k) : e.line());
                }
                done.accept(new Ran(r.end() == RunResult.End.COMPLETED, sent, r.reason(), r.message()));
            }
        }, token);
        if (!s.started()) return new Started(false, s.refusal().code(), s.refusal().message(), s.refusal().detail());
        return new Started(true, null, null, null);
    }

    private static Presser presser(Person p) {
        return new Presser(p.id(), p.name());
    }

    private static RunRequest request(Person recipient, Plan plan, String source) {
        List<RunItem> items = new ArrayList<>();
        if (plan.presetId() != null) {
            items.add(RunItem.ref(plan.presetId()));
        } else {
            for (String line : plan.lines()) items.add(RunItem.line(line));
        }
        String label = GiftService.OWN_SOURCE.equals(source) ? "Generous Operator" : "Generous Operator for " + source;
        if (label.length() > MAX_LABEL) label = label.substring(0, MAX_LABEL);
        return new RunRequest(items, label, new Target(recipient.id(), recipient.name()));
    }
}
