import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class Hotel_Reservation_System extends JFrame {

    // ================= ENUM =================
    enum RoomType {
        STANDARD(1200),
        DELUXE(2500),
        SUITE(4000);

        int price;

        RoomType(int price) {
            this.price = price;
        }
    }

    // ================= MODEL =================
    static class Room {
        int roomNo;
        RoomType type;
        boolean booked;

        Room(int roomNo, RoomType type) {
            this.roomNo = roomNo;
            this.type = type;
            this.booked = false;
        }
    }

    static class Booking {
        String id;
        String name;
        int roomNo;
        RoomType type;
        int days;
        int amount;

        Booking(String id, String name, int roomNo, RoomType type, int days, int amount) {
            this.id = id;
            this.name = name;
            this.roomNo = roomNo;
            this.type = type;
            this.days = days;
            this.amount = amount;
        }

        public String toString() {
            return id + "," + name + "," + roomNo + "," + type + "," + days + "," + amount;
        }
    }

    // ================= DATA =================
    ArrayList<Room> rooms = new ArrayList<>();
    HashMap<String, Booking> bookings = new HashMap<>();
    final String FILE = "bookings.txt";

    // ================= UI =================
    JTable roomTable, bookingTable;
    DefaultTableModel roomModel, bookingModel;
    JTextField nameField, daysField, cancelField;
    JComboBox<String> typeBox;
    JTextArea logArea;

    // ================= INIT =================
    public Hotel_Reservation_System() {

        setTitle("Hotel Reservation System");
        setSize(900, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        initRooms();
        loadBookings();

        // ================= TOP PANEL =================
        JPanel top = new JPanel(new GridLayout(2, 1));

        JLabel title = new JLabel("HOTEL RESERVATION SYSTEM", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));

        top.add(title);
        add(top, BorderLayout.NORTH);

        // ================= CENTER TABLES =================
        JPanel center = new JPanel(new GridLayout(1, 2));

        roomModel = new DefaultTableModel(new String[]{"Room", "Type", "Status", "Price"}, 0);
        roomTable = new JTable(roomModel);

        bookingModel = new DefaultTableModel(new String[]{"ID", "Name", "Room", "Type", "Days", "Amount"}, 0);
        bookingTable = new JTable(bookingModel);

        center.add(new JScrollPane(roomTable));
        center.add(new JScrollPane(bookingTable));

        add(center, BorderLayout.CENTER);

        // ================= BOTTOM PANEL =================
        JPanel bottom = new JPanel(new GridLayout(3, 1));

        JPanel form = new JPanel();

        nameField = new JTextField(8);
        daysField = new JTextField(4);
        cancelField = new JTextField(8);

        typeBox = new JComboBox<>(new String[]{"STANDARD", "DELUXE", "SUITE"});

        JButton bookBtn = new JButton("BOOK");
        JButton cancelBtn = new JButton("CANCEL");
        JButton refreshBtn = new JButton("REFRESH");

        form.add(new JLabel("Name:"));
        form.add(nameField);
        form.add(new JLabel("Type:"));
        form.add(typeBox);
        form.add(new JLabel("Days:"));
        form.add(daysField);
        form.add(bookBtn);

        form.add(new JLabel("Cancel ID:"));
        form.add(cancelField);
        form.add(cancelBtn);
        form.add(refreshBtn);

        bottom.add(form);

        logArea = new JTextArea(4, 20);
        logArea.setEditable(false);
        bottom.add(new JScrollPane(logArea));

        add(bottom, BorderLayout.SOUTH);

        // ================= ACTIONS =================
        bookBtn.addActionListener(e -> bookRoom());
        cancelBtn.addActionListener(e -> cancelBooking());
        refreshBtn.addActionListener(e -> refreshUI());

        refreshUI();

        setVisible(true);
    }

    // ================= ROOM INIT =================
    void initRooms() {

        for (int i = 101; i <= 105; i++)
            rooms.add(new Room(i, RoomType.STANDARD));

        for (int i = 201; i <= 205; i++)
            rooms.add(new Room(i, RoomType.DELUXE));

        for (int i = 301; i <= 303; i++)
            rooms.add(new Room(i, RoomType.SUITE));
    }

    // ================= BOOK ROOM =================
    void bookRoom() {

        String name = nameField.getText();
        int days = Integer.parseInt(daysField.getText());
        RoomType type = RoomType.valueOf(typeBox.getSelectedItem().toString());

        for (Room r : rooms) {
            if (r.type == type && !r.booked) {

                int amount = r.type.price * days;

                JOptionPane.showMessageDialog(this,
                        "Payment Successful: ₹" + amount);

                r.booked = true;

                String id = "B" + new Random().nextInt(99999);

                Booking b = new Booking(id, name, r.roomNo, type, days, amount);
                bookings.put(id, b);

                saveBookings();

                log("Booked Room " + r.roomNo + " ID: " + id);

                refreshUI();
                return;
            }
        }

        JOptionPane.showMessageDialog(this, "No rooms available!");
    }

    // ================= CANCEL =================
    void cancelBooking() {

        String id = cancelField.getText();

        if (!bookings.containsKey(id)) {
            JOptionPane.showMessageDialog(this, "Invalid ID!");
            return;
        }

        Booking b = bookings.remove(id);

        for (Room r : rooms) {
            if (r.roomNo == b.roomNo) {
                r.booked = false;
            }
        }

        saveBookings();

        log("Cancelled Booking " + id);

        refreshUI();
    }

    // ================= SAVE FILE =================
    void saveBookings() {

        try (PrintWriter pw = new PrintWriter(new FileWriter(FILE))) {
            for (Booking b : bookings.values()) {
                pw.println(b.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ================= LOAD FILE =================
    void loadBookings() {

        File f = new File(FILE);
        if (!f.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(FILE))) {

            String line;
            while ((line = br.readLine()) != null) {

                String[] p = line.split(",");

                Booking b = new Booking(
                        p[0], p[1], Integer.parseInt(p[2]),
                        RoomType.valueOf(p[3]),
                        Integer.parseInt(p[4]),
                        Integer.parseInt(p[5])
                );

                bookings.put(b.id, b);

                for (Room r : rooms) {
                    if (r.roomNo == b.roomNo) {
                        r.booked = true;
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ================= UI REFRESH =================
    void refreshUI() {

        roomModel.setRowCount(0);
        bookingModel.setRowCount(0);

        for (Room r : rooms) {
            roomModel.addRow(new Object[]{
                    r.roomNo,
                    r.type,
                    r.booked ? "BOOKED" : "AVAILABLE",
                    r.type.price
            });
        }

        for (Booking b : bookings.values()) {
            bookingModel.addRow(new Object[]{
                    b.id, b.name, b.roomNo, b.type, b.days, b.amount
            });
        }
    }

    // ================= LOG =================
    void log(String msg) {
        logArea.append(msg + "\n");
    }

    // ================= MAIN =================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Hotel_Reservation_System::new);
    }
}