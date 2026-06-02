package com.whiteboard.cleanrecord;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends AppCompatActivity {

    private ProjectDatabaseHelper dbHelper;
    private RecyclerView rvRecentProjects;
    private ProjectAdapter projectAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        dbHelper = new ProjectDatabaseHelper(this);

        // TARGET FIXED ID: Maps perfectly to your actual RecyclerView grid structure
        rvRecentProjects = findViewById(R.id.rvRecentProjects);
        if (rvRecentProjects != null) {
            rvRecentProjects.setLayoutManager(new GridLayoutManager(this, 3)); // 3 columns matching your design layout
        }

        // TARGET FIXED ID: Wired cleanly to your custom click element
        View btnAddProject = findViewById(R.id.actionNewProject);
        if (btnAddProject != null) {
            btnAddProject.setOnClickListener(v -> {
                Intent intent = new Intent(HomeActivity.this, MainActivity.class);
                startActivity(intent);
            });
        }

        loadDashboardProjectCards();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDashboardProjectCards();
    }

    private void loadDashboardProjectCards() {
        List<ProjectModel> projectsList = dbHelper.getAllProjects();
        if (rvRecentProjects != null) {
            projectAdapter = new ProjectAdapter(projectsList);
            rvRecentProjects.setAdapter(projectAdapter);
        }
    }

    private void showCardOptionsMenu(View anchorView, ProjectModel project) {
        PopupMenu popup = new PopupMenu(this, anchorView);
        popup.getMenu().add("Rename Lesson");
        popup.getMenu().add("Delete Project");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getTitle().equals("Rename Lesson")) {
                promptRenameDialogSequence(project);
                return true;
            } else if (item.getTitle().equals("Delete Project")) {
                promptDeleteConfirmation(project);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void promptRenameDialogSequence(ProjectModel project) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Lesson");

        final EditText inputField = new EditText(this);
        inputField.setText(project.getName());
        inputField.setSelectAllOnFocus(true);
        builder.setView(inputField);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newTitle = inputField.getText().toString().trim();
            if (!newTitle.isEmpty()) {
                dbHelper.updateProject(project.getId(), newTitle, project.getJsonContent());
                loadDashboardProjectCards();
                Toast.makeText(HomeActivity.this, "Project renamed successfully", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void promptDeleteConfirmation(ProjectModel project) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Project")
                .setMessage("Are you sure you want to permanently delete this lesson?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    dbHelper.deleteProject(project.getId());
                    loadDashboardProjectCards();
                    Toast.makeText(HomeActivity.this, "Project deleted cleanly", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ==========================================
    // RECYCLERVIEW REBOUND ADAPTER IMPLEMENTATION
    // ==========================================
    private class ProjectAdapter extends RecyclerView.Adapter<ProjectAdapter.ProjectViewHolder> {
        
        private final List<ProjectModel> projects;
        private final SimpleDateFormat dateFormatter = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

        public ProjectAdapter(List<ProjectModel> projects) {
            this.projects = projects;
        }

        @NonNull
        @Override
        public ProjectViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_project_grid, parent, false);
            return new ProjectViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ProjectViewHolder holder, int position) {
            ProjectModel project = projects.get(position);
            holder.txtProjectTitle.setText(project.getName());
            
            try {
                holder.txtProjectDate.setText(dateFormatter.format(new Date(project.getLastModified())));
            } catch (Exception e) {
                holder.txtProjectDate.setText("Whiteboard Lesson");
            }

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(HomeActivity.this, MainActivity.class);
                intent.putExtra("PROJECT_ID", project.getId());
                startActivity(intent);
            });

            holder.btnProjectOptions.setOnClickListener(v -> showCardOptionsMenu(v, project));
        }

        @Override
        public int getItemCount() {
            return projects.size();
        }

        class ProjectViewHolder extends RecyclerView.ViewHolder {
            TextView txtProjectTitle;
            TextView txtProjectDate;
            View btnProjectOptions;

            public ProjectViewHolder(@NonNull View itemView) {
                super(itemView);
                txtProjectTitle = itemView.findViewById(R.id.txtProjectTitle);
                txtProjectDate = itemView.findViewById(R.id.txtProjectDate);
                btnProjectOptions = itemView.findViewById(R.id.btnProjectOptions);
            }
        }
    }
}
