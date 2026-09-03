package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a facade JSON key (e.g. {@code applicants}, {@code caseParties},
 * {@code riskProfilingResponseVOList}) to a physical DB table name in the
 * dataModel. Used only by {@link FacadeValidator} for the advisory cross-check.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>Explicit override from {@code profile.facadeKeyOverrides} — final word.</li>
 *   <li>Case-insensitive match against entity name → its physical table name.</li>
 *   <li>Singular/plural strip against entity names, including Latin plurals
 *       (data→datum, criteria→criterion).</li>
 *   <li>WaveMaker {@code VO} / {@code VOList} suffix strip, then retry.</li>
 *   <li><b>endsWith unique match</b> — for schemas where children are named
 *       {@code ParentChild} (e.g. {@code CaseDevice}, {@code CaseCreditApp}),
 *       find entities whose entityName ends with the (singularised, capitalised)
 *       key. Accepted only when exactly one entity matches — ambiguity means
 *       we don't guess, and the entry shows as "unmapped" so the user can add
 *       an explicit override.</li>
 * </ol>
 * <p>
 * Never blocks anything — an unresolved key becomes a "unmapped" cross-check
 * entry in the plan preview.
 */
public final class FacadeKeyResolver {

    /** English handles most plurals via s/es/ies rules. These are the exceptions. */
    private static final Map<String, String> LATIN_PLURAL_SINGULAR = Map.of(
            "data", "datum",
            "criteria", "criterion",
            "phenomena", "phenomenon",
            "media", "medium"
    );

    private final Map<String, DataModel.Table> byEntity;    // entityName lower → Table
    private final List<DataModel.Table> allTables;
    private final Map<String, String> overrides;            // facadeKey lower → tableName

    public FacadeKeyResolver(DataModel model, Map<String, String> facadeKeyOverrides) {
        this.byEntity = new HashMap<>();
        this.allTables = new ArrayList<>();
        if (model != null && model.tables() != null) {
            for (DataModel.Table t : model.tables()) {
                if (t.entityName() != null) byEntity.put(t.entityName().toLowerCase(Locale.ROOT), t);
                if (t.name() != null) allTables.add(t);
            }
        }
        this.overrides = new LinkedHashMap<>();
        if (facadeKeyOverrides != null) {
            facadeKeyOverrides.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    this.overrides.put(k.toLowerCase(Locale.ROOT), v.trim());
                }
            });
        }
    }

    /** @return the physical table name (uppercase-normalised) matching {@code facadeKey},
     *          or empty if no unique match. */
    public Optional<String> toTableName(String facadeKey) {
        if (facadeKey == null || facadeKey.isBlank()) return Optional.empty();
        String lower = facadeKey.toLowerCase(Locale.ROOT);

        // 1. Explicit override — could be entity name OR physical table name
        String overrideTarget = overrides.get(lower);
        if (overrideTarget != null) {
            DataModel.Table byName = byEntity.get(overrideTarget.toLowerCase(Locale.ROOT));
            if (byName != null && byName.name() != null) return Optional.of(FkGraph.norm(byName.name()));
            return Optional.of(FkGraph.norm(overrideTarget));   // assume already a physical table name
        }

        // 2/3. Direct + English + Latin singular/plural
        Optional<DataModel.Table> t = matchVariants(lower);
        if (t.isPresent()) return t.map(x -> FkGraph.norm(x.name()));

        // 4. Strip VO/VOList and retry
        String stripped = stripVo(lower);
        if (!stripped.equals(lower)) {
            t = matchVariants(stripped);
            if (t.isPresent()) return t.map(x -> FkGraph.norm(x.name()));
        }

        // 5. endsWith unique match — critical for composite entity names.
        //    Handles device → CaseDevice, statusHistories → CaseStatusHistory, etc.
        t = endsWithUnique(singularise(stripped));
        if (t.isPresent()) return t.map(x -> FkGraph.norm(x.name()));

        return Optional.empty();
    }

    private Optional<DataModel.Table> matchVariants(String lower) {
        if (byEntity.containsKey(lower)) return Optional.of(byEntity.get(lower));
        // Latin plurals: data → datum, criteria → criterion, media → medium
        String latin = LATIN_PLURAL_SINGULAR.get(lower);
        if (latin != null && byEntity.containsKey(latin)) return Optional.of(byEntity.get(latin));
        // English plurals
        if (lower.endsWith("ies") && lower.length() > 3) {
            String s = lower.substring(0, lower.length() - 3) + "y";
            if (byEntity.containsKey(s)) return Optional.of(byEntity.get(s));
        }
        if (lower.endsWith("s")) {
            String s = lower.substring(0, lower.length() - 1);
            if (byEntity.containsKey(s)) return Optional.of(byEntity.get(s));
            if (lower.endsWith("es")) {
                String s2 = lower.substring(0, lower.length() - 2);
                if (byEntity.containsKey(s2)) return Optional.of(byEntity.get(s2));
            }
        }
        if (byEntity.containsKey(lower + "s")) return Optional.of(byEntity.get(lower + "s"));
        return Optional.empty();
    }

    /**
     * Find entities whose entityName ends with {@code suffix} (case-insensitive).
     * Returns present only when exactly one entity matches — avoids guessing
     * when multiple entities have the same trailing word.
     */
    private Optional<DataModel.Table> endsWithUnique(String suffix) {
        if (suffix == null || suffix.isBlank()) return Optional.empty();
        String s = suffix.toLowerCase(Locale.ROOT);
        List<DataModel.Table> matches = new ArrayList<>(2);
        for (DataModel.Table t : allTables) {
            if (t.entityName() == null) continue;
            String en = t.entityName().toLowerCase(Locale.ROOT);
            if (en.endsWith(s)) matches.add(t);
            if (matches.size() > 1) return Optional.empty();
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** English singularisation + Latin exceptions. Best-effort — for lookup only. */
    private static String singularise(String lower) {
        if (lower == null || lower.isBlank()) return lower;
        String latin = LATIN_PLURAL_SINGULAR.get(lower);
        if (latin != null) return latin;
        if (lower.endsWith("ies") && lower.length() > 3) return lower.substring(0, lower.length() - 3) + "y";
        if (lower.endsWith("es") && lower.length() > 2) return lower.substring(0, lower.length() - 2);
        if (lower.endsWith("s") && lower.length() > 1) return lower.substring(0, lower.length() - 1);
        return lower;
    }

    private static String stripVo(String lower) {
        if (lower.endsWith("volist")) return lower.substring(0, lower.length() - "volist".length());
        if (lower.endsWith("vo"))     return lower.substring(0, lower.length() - "vo".length());
        return lower;
    }
}
