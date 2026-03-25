package org.example;

import org.example.additional.Person;
import org.example.jickle.JickleDeserializer;
import org.example.jickle.JickleSerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

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
            List<Person> rebyatav2 = List.of(unknown, python_dev);
            Person[] semeyka = {python_dev, dimandr, unknown};

            JickleSerializer serializer = new JickleSerializer(false);

            serializer.dump(unknown, "orphan.json");
            serializer.dump(rebyata, "rebyata.json");
            serializer.dump(semeyka, "family.json");
            serializer.dumpList(rebyatav2, "rebyata_list.json");

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