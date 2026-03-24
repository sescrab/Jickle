package org.example;

import org.example.jickle.JickleDeserializer;
import org.example.jickle.JickleSerializer;
import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@JicklableClass
public class Person {
    public int age;
    public String name;

    @JickleIgnore
    public String bitcoin_wallet_password;

    public Person parent;

    public Person() {
    }

    public Person(int age, String name, Person parent) {
        this.age = age;
        this.name = name;
        this.parent = parent;
        this.bitcoin_wallet_password = "1337 BTC: " + age * 50;
    }

    @Override
    public String toString() {
        return "Person{" +
                "age=" + age +
                ", name='" + name + '\'' +
                ", parent=" + (parent != null ? parent.name : "null") +
                '}';
    }

    // Добавлено специально для корректного сравнения в тесте
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Person person = (Person) o;
        return age == person.age &&
                Objects.equals(name, person.name) &&
                Objects.equals(parent, person.parent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(age, name, parent);
    }
}

public class Main {
    public static void main(String[] args) {
        try {
            Person python_dev = new Person(69, "True programmer", null);
            Person dimandr = new Person(20, "Dimas", python_dev);
            Person unknown = new Person(5, "Orphan", dimandr);

            System.out.println("Before Jickling:");
            System.out.println(python_dev);
            System.out.println(dimandr);
            System.out.println(unknown);

            List<Person> rebyata = List.of(dimandr);
            Person[] semeyka = {python_dev, dimandr, unknown};

            JickleSerializer serializer = new JickleSerializer(false);

            serializer.dump(unknown, "orphan.json");
            serializer.dump(rebyata, "rebyata.json");
            serializer.dump(semeyka, "family.json");

            String content = Files.readString(Paths.get("rebyata.json"), StandardCharsets.UTF_8);
            System.out.println("\nAfter Jickling (rebyata.json):");
            System.out.println(content);

            JickleDeserializer deserializer = new JickleDeserializer(false);
            List<Object> result = deserializer.load("family.json");

            // family.json содержит массив Person[] как корневой объект
            Object[] deserializedArray = (Object[]) result.get(0);

            System.out.println("\nDeserialized family (" + deserializedArray.length + " persons):");
            for (Object p : deserializedArray) {
                System.out.println(p);
            }

            boolean success = Arrays.deepEquals(semeyka, deserializedArray);
            System.out.println("\nComparison result: " + (success ? "Pobeda!!!" : "AntiPobeda..."));


        } catch (Exception err) {
            err.printStackTrace();
        }
    }
}