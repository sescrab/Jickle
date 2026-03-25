package org.example;

import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;

import java.util.Objects;

@JicklableClass
public class Person {
    public int age;
    public String name;

    @JickleIgnore
    public String bitcoin_wallet_password;

    public Person parent;

    public Person() {}

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