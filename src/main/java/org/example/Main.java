package org.example;

import org.example.jickle.JickleSerializer;
import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@JicklableClass
class Person {
    public int age;
    public String name;

    @JickleIgnore
    public String bitcoin_wallet_password;

    public Person parent;

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
                ", secret='" + bitcoin_wallet_password + '\'' +
                ", parent=" + (parent != null ? parent.name : "null") +
                '}';
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

            JickleSerializer serializer = new JickleSerializer(false);

            serializer.dump(unknown, "orphan.json");
            serializer.dump(rebyata, "rebyata.json");

            String content = Files.readString(Paths.get("rebyata.json"), StandardCharsets.UTF_8);
            System.out.println("After Jickling:");
            System.out.println(content);

        } catch (Exception err) {
            err.printStackTrace();
        }
    }
}