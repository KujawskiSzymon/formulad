package formulad;

import javax.swing.*;
import java.awt.*;
import java.text.NumberFormat;

public class SettingsField extends JPanel {
    private final JPanel parent;
    private final String name;
    private final JTextField lapsField;
    private final int min;
    private final int max;

    public SettingsField(JPanel parent, String name, String initialValue, int minValue, int maxValue) {
        super(new FlowLayout(FlowLayout.LEFT));
        final JLabel label = new JLabel(name);
        final JTextField field = new JTextField(initialValue);
        field.setPreferredSize(new Dimension(100, 20));
        add(label);
        add(field);
        this.parent = parent;
        this.name = name;
        this.lapsField = field;
        min = minValue;
        max = maxValue;
    }

    public int getValue() throws NumberFormatException {
        try {
            int result = Integer.parseInt(lapsField.getText());
            if (result < min || result > max) {
                final String message = "Please choose value " + min + "-" + max + " for " + name;
                JOptionPane.showConfirmDialog(parent, message, "Error", JOptionPane.DEFAULT_OPTION);
                throw new NumberFormatException(message);
            }
            return result;
        } catch (NumberFormatException e) {
            JOptionPane.showConfirmDialog(parent, "Invalid value for " + name + ": " + lapsField.getText(), "Error", JOptionPane.DEFAULT_OPTION);
            throw e;
        }
    }
}
