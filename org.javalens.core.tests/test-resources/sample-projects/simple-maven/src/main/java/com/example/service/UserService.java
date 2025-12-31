package com.example.service;

import com.example.Calculator;
import java.util.ArrayList;
import java.util.List;

/**
 * Service class for user operations.
 * Used to test cross-file references and imports.
 */
public class UserService {
    private final List<String> users;
    private final Calculator calculator;

    /**
     * Creates a new UserService.
     */
    public UserService() {
        this.users = new ArrayList<>();
        this.calculator = new Calculator();
    }

    /**
     * Adds a user to the service.
     * @param username the username to add
     * @return true if added successfully
     */
    public boolean addUser(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        return users.add(username);
    }

    /**
     * Removes a user from the service.
     * @param username the username to remove
     * @return true if removed successfully
     */
    public boolean removeUser(String username) {
        return users.remove(username);
    }

    /**
     * Gets the number of users.
     * @return user count
     */
    public int getUserCount() {
        return users.size();
    }

    /**
     * Calculates the sum of two values (using Calculator).
     * @param a first value
     * @param b second value
     * @return the sum
     */
    public int calculateSum(int a, int b) {
        return calculator.add(a, b);
    }

    /**
     * Gets all usernames.
     * @return list of usernames
     */
    public List<String> getUsers() {
        return new ArrayList<>(users);
    }
}
