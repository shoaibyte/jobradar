package dev.shoaib.jobradar.sources.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal resolver for Nuxt's {@code __NUXT_DATA__} payload format (see
 * notes/phase0-orchestrator.md -- japan-dev.com moved from Next.js to Nuxt after the
 * spec was written, so the assumed {@code __NEXT_DATA__} plain-object tree does not
 * exist; the real payload is a flat JSON array using a devalue-style encoding).
 *
 * <p>The payload is a single top-level JSON array. Each array slot is either a
 * primitive leaf (string/number/boolean/null) or a container (JSON object or array)
 * whose values are themselves <em>indices</em> into the same top-level array, resolved
 * recursively. A small set of two-element arrays are Vue/Nuxt "reducer" tags
 * (e.g. {@code ["ShallowReactive", 7]}) that transparently unwrap to the value at the
 * referenced index rather than being treated as a literal 2-element list.
 */
final class NuxtDevaluePayload {

    private static final Set<String> REDUCER_TAGS = Set.of(
        "ShallowReactive", "Reactive", "Ref", "ShallowRef", "EmptyRef", "NuxtError");

    private final List<?> raw;
    private final Map<Integer, Object> cache = new HashMap<>();
    private final Set<Integer> resolving = new HashSet<>();

    NuxtDevaluePayload(List<?> raw) {
        this.raw = raw;
    }

    /** Fully resolves the value at {@code index}, recursively expanding nested indices. */
    Object resolve(int index) {
        if (cache.containsKey(index)) {
            return cache.get(index);
        }
        if (index < 0 || index >= raw.size() || !resolving.add(index)) {
            // Out of range or a cycle -- shouldn't happen for well-formed payloads, but
            // this is untrusted third-party markup, so fail soft rather than loop/crash.
            return null;
        }
        Object v = raw.get(index);
        Object out;
        if (v instanceof List<?> list) {
            if (list.size() == 2 && list.get(0) instanceof String tag && REDUCER_TAGS.contains(tag)
                && list.get(1) instanceof Number ref) {
                out = resolve(ref.intValue());
            } else {
                List<Object> resolved = new ArrayList<>(list.size());
                for (Object el : list) {
                    resolved.add(el instanceof Number n ? resolve(n.intValue()) : el);
                }
                out = resolved;
            }
        } else if (v instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object val = e.getValue();
                resolved.put(String.valueOf(e.getKey()), val instanceof Number n ? resolve(n.intValue()) : val);
            }
            out = resolved;
        } else {
            out = v;
        }
        resolving.remove(index);
        cache.put(index, out);
        return out;
    }

    /**
     * Walks the fully-resolved tree rooted at index 0 looking for job-hit arrays: lists
     * where every element is a map containing at least {@code title} and
     * {@code company_name} keys. Duck-typed rather than keyed off a fixed path (e.g.
     * {@code data.algolia-state...results[0].hits}) so it survives Nuxt/Algolia
     * composable renames between pages.
     */
    List<Map<String, Object>> findJobHitLists() {
        List<Map<String, Object>> found = new ArrayList<>();
        collectJobHitLists(resolve(0), found, new HashSet<>());
        return found;
    }

    @SuppressWarnings("unchecked")
    private void collectJobHitLists(Object node, List<Map<String, Object>> found, Set<Object> seen) {
        if (node == null || !seen.add(node)) {
            return;
        }
        if (node instanceof List<?> list) {
            if (!list.isEmpty() && list.stream().allMatch(NuxtDevaluePayload::looksLikeJobHit)) {
                for (Object el : list) {
                    found.add((Map<String, Object>) el);
                }
            } else {
                for (Object el : list) {
                    collectJobHitLists(el, found, seen);
                }
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Object val : map.values()) {
                collectJobHitLists(val, found, seen);
            }
        }
    }

    private static boolean looksLikeJobHit(Object el) {
        return el instanceof Map<?, ?> m && m.containsKey("title") && m.containsKey("company_name");
    }
}
