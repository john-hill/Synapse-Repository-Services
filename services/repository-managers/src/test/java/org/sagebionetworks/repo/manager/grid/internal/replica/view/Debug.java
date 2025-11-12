package org.sagebionetworks.repo.manager.grid.internal.replica.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Debug {


	public static void main(String[] args) {

        List<String> products = new ArrayList<>(
            Arrays.asList("Laptop", "Mouse", "Keyboard", "Fax Machine", "Monitor")
        );
        
        System.out.println("Original list: " + products);

        try {
            for (String product : products) {
                if (product.contains("o")) {
                    products.remove(product); 
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Filtered list: " + products);
    }
}
