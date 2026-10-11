package ru.practicum.ewm.stats.serialization;

import ru.practicum.ewm.stats.avro.ActionTypeAvro;

public record ActionWeights(double view, double register, double like) {
    public ActionWeights {
        for (double value : new double[]{view, register, like}) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Вес действия должен быть конечным числом от 0 до 1");
            }
        }
    }

    public double weight(ActionTypeAvro action) {
        return switch (action) {
            case VIEW -> view;
            case REGISTER -> register;
            case LIKE -> like;
        };
    }
}
