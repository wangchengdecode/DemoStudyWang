package wang.demo.root.camera2demo;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import wang.demo.root.R;


/**
        官方demo
 */
public class CameraActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        if (null == savedInstanceState) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, Camera2BasicFragment.newInstance())
                    .commit();
        }
    }

}