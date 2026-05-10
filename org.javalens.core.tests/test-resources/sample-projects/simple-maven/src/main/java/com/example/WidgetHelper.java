package com.example;

public class WidgetHelper {

    public String describe(FieldHolder holder) {
        return "Pet: " + holder.pet;
    }

    public void swap(FieldHolder holder, Animal newPet) {
        holder.pet = newPet;
    }
}
