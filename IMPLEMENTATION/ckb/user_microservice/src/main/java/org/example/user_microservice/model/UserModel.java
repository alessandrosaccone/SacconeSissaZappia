package org.example.user_microservice.model;

import javax.persistence.*;

@Entity
@Table(name="user_model")
public class UserModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private int role;

    public UserModel() {
    }
    public UserModel(String username, int role) {
        this.username = username;
        this.role = role;
    }

    public String getUsername() {
        return this.username;
    }
    public void setRole(int role) {
        this.role = role;
    }
}