package org.example;

import org.example.jickle.JickleDeserializer;
import org.example.jickle.JickleSerializer;
import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;

import java.io.IOException;
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

    public Person(){};
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
    public static void main(String[] args) throws IOException {
        String path = "test.json";
        JickleDeserializer deserializer = new JickleDeserializer(false);
        List<Object> objects = deserializer.load(path);
    }
}