package com.sfquiz.service;

import com.sfquiz.dto.ChoiceDto;
import com.sfquiz.dto.QuestionDto;
import com.sfquiz.entity.Choice;
import com.sfquiz.entity.Question;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-session randomization layer applied to question DTOs before they go out
 *  to the client. Three transforms, in this order:
 *  <ol>
 *    <li>Replace well-known Salesforce fictional company names (Universal
 *        Containers, Northern Trail Outfitters, etc.) with rotated aliases so
 *        repeat test-takers can't memorize who's the bank vs. the consultancy.</li>
 *    <li>Randomize purely-numeric values in <strong>incorrect</strong> choices
 *        (e.g. wrong distractor "5000" → "4200"). Correct choices are never
 *        touched. The number's magnitude is preserved.</li>
 *    <li>Shuffle the order of choices and relabel A/B/C/D so the correct
 *        answer's position varies.</li>
 *  </ol>
 *  Choice IDs are preserved — submit still validates against the DB. */
@Service
public class QuestionRandomizer {

    /** Canonical Salesforce fictional company names that appear in study material. */
    private static final List<String> REAL_COMPANIES = List.of(
            "Universal Containers",
            "Northern Trail Outfitters",
            "Cloud Kicks",
            "Get Cloudy Consulting",
            "Salesforce Time",
            "Ursa Major Solar",
            "Madison Rivers",
            "Pyrocell",
            "Trailblazer Inc",
            "Acme Corp",
            "AW Computing"
    );

    /** Alternatives randomly mapped onto the canonical names each session. */
    private static final List<String> ALT_NAMES = List.of(
            "Golf Forever",
            "Window Shining Inc",
            "Tech Roar",
            "Greenleaf Capital",
            "Vista Bicycles",
            "Harbor Logistics",
            "Nimbus Cloud Co",
            "Brightwave Energy",
            "Crestpoint Realty",
            "Iron Anvil Mfg",
            "Pacific Pet Foods",
            "BrightForge",
            "Atlas Aerospace",
            "Sunridge Coffee",
            "Quartzpoint Group"
    );

    /** Matches a choice that is "mostly a number" — optional currency, digits
     *  with grouping or decimals, optional unit/suffix (%, GB, users, …). */
    private static final Pattern NUMERIC_CHOICE =
            Pattern.compile("^([$£€])?\\s*([0-9]+(?:[,.][0-9]+)*)\\s*(.*?)$");

    /** Build a fresh canonical→alternative map. Each call produces a new mix. */
    public Map<String, String> buildSessionNameMap() {
        Random rnd = new Random();
        List<String> alts = new ArrayList<>(ALT_NAMES);
        Collections.shuffle(alts, rnd);
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < REAL_COMPANIES.size(); i++) {
            map.put(REAL_COMPANIES.get(i), alts.get(i % alts.size()));
        }
        return map;
    }

    public QuestionDto randomize(Question q, Map<String, String> nameMap) {
        Random rnd = new Random();

        // Step 1: name substitution on the question stem
        String stem = applyNames(q.getText(), nameMap);

        // Step 2: name sub on every choice; numeric randomization on incorrect ones
        List<Choice> source = q.getChoices();
        List<ChoiceDto> dtos = new ArrayList<>(source.size());
        for (Choice c : source) {
            String t = applyNames(c.getText(), nameMap);
            if (!c.isCorrect()) {
                t = maybeRandomizeNumeric(t, rnd);
            }
            dtos.add(new ChoiceDto(c.getId(), c.getLabel(), t));
        }

        // Step 3: shuffle order + relabel A/B/C/D so positions don't memorize
        Collections.shuffle(dtos, rnd);
        for (int i = 0; i < dtos.size(); i++) {
            dtos.get(i).setLabel(String.valueOf((char) ('A' + i)));
        }

        QuestionDto out = new QuestionDto();
        out.setId(q.getId());
        out.setNumber(q.getNumber());
        out.setType(q.getType().name());
        out.setText(stem);
        out.setChoices(dtos);
        return out;
    }

    // --- helpers ---------------------------------------------------------------

    private String applyNames(String text, Map<String, String> map) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Map.Entry<String, String> e : map.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private String maybeRandomizeNumeric(String original, Random rnd) {
        if (original == null) return null;
        String trimmed = original.trim();
        if (trimmed.isEmpty()) return original;
        Matcher m = NUMERIC_CHOICE.matcher(trimmed);
        if (!m.matches()) return original;
        String currency = m.group(1) == null ? "" : m.group(1);
        String numStr = m.group(2).replace(",", "");
        String suffix = m.group(3) == null ? "" : m.group(3);
        // Require the choice to actually be predominantly numeric — if the
        // suffix is long prose, leave it alone (e.g. "5 minutes after creation").
        if (suffix.length() > 16) return original;
        try {
            double v = Double.parseDouble(numStr);
            if (v <= 0) return original;
            double factor;
            do {
                factor = 0.3 + rnd.nextDouble() * 2.7;
            } while (Math.abs(factor - 1.0) < 0.15);
            double newV = v * factor;
            String newNum = niceRound(newV);
            return currency + newNum + (suffix.isEmpty() ? "" : " " + suffix.trim());
        } catch (NumberFormatException e) {
            return original;
        }
    }

    /** Round to a "nice" number so wrong answers don't look like 7,318. */
    private String niceRound(double v) {
        if (v < 10)       return String.valueOf(Math.max(1, Math.round(v)));
        if (v < 100)      return String.valueOf(Math.max(5,  Math.round(v / 5)  * 5));
        if (v < 1_000)    return String.valueOf(Math.max(50, Math.round(v / 50) * 50));
        if (v < 10_000)   return String.valueOf(Math.round(v / 100)  * 100);
        if (v < 100_000)  return String.valueOf(Math.round(v / 500)  * 500);
        return String.valueOf(Math.round(v / 1000) * 1000);
    }
}
