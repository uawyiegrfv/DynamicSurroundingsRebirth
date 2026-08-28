package org.orecruncher.dsurround.lib;

import org.orecruncher.dsurround.lib.random.IRandomizer;
import org.orecruncher.dsurround.lib.random.Randomizer;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Classic WeightTable for random weighted selection.
 */
public class WeightTable {

    public static <T> Optional<T> makeSelection(final Stream<? extends IItem<T>> inputStream) {
        return makeSelection(inputStream, Randomizer.current());
    }

    public static <T> Optional<T> makeSelection(final Stream<? extends IItem<T>> inputStream, IRandomizer randomizer) {
        return makeSelection(inputStream.toList(), randomizer);
    }

    public static <T> Optional<T> makeSelection(final List<? extends IItem<T>> selections, IRandomizer randomizer) {
        if (selections.isEmpty())
            return Optional.empty();

        if (selections.size() == 1)
            return Optional.of(selections.get(0).data());

        int totalWeight = 0;
        for (var item : selections) {
            totalWeight += item.weight();
        }
        if (totalWeight <= 0)
            return Optional.empty();

        int selected = randomizer.nextInt(totalWeight);
        int cumulative = 0;
        for (var item : selections) {
            cumulative += item.weight();
            if (selected < cumulative)
                return Optional.of(item.data());
        }
        return Optional.of(selections.get(selections.size() - 1).data());
    }

    // Weighted is a record in 26.1, can't extend it. Define our own interface.
    public interface IItem<T> {
        T data();
        int weight();
    }
}