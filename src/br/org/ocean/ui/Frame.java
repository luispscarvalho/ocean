package br.org.ocean.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.swing.JFileChooser;

import org.repositoryminer.codesmell.direct.BrainClass;
import org.repositoryminer.codesmell.direct.BrainMethod;
import org.repositoryminer.codesmell.direct.DataClass;
import org.repositoryminer.codesmell.direct.GodClass;
import org.repositoryminer.codesmell.direct.LongMethod;
import org.repositoryminer.listener.mining.IMiningListener;
import org.repositoryminer.mining.RepositoryMiner;
import org.repositoryminer.model.Reference;
import org.repositoryminer.model.Repository;
import org.repositoryminer.parser.java.JavaParser;
import org.repositoryminer.persistence.Connection;
import org.repositoryminer.scm.ISCM;
import org.repositoryminer.scm.ReferenceType;
import org.repositoryminer.scm.SCMFactory;
import org.repositoryminer.scm.SCMType;

import br.edu.ufba.ocean.ui.UI;
import br.org.ocean.IExportProgressListener;
import br.org.ocean.ILogger;
import br.org.ocean.exporter.OntoExporter;

@SuppressWarnings("serial")
public class Frame extends UI implements IMiningListener, IExportProgressListener, ILogger, Runnable {

	private static final String TITLE = "OCEAN v0.1";
	// TODO no hardcoded definitions expected for stable releases
	private static final String DEFAULT_PROJECT = "/misc/workspace/doutorado/workspaces/repos/guava";

	private Properties props;
	private RepositoryMiner miner;

	public Frame() {
	}

	private Frame getThis() {
		return this;
	}

	public void configure() {
		setTitle(TITLE);
		setModal(true);

		log.setEditable(false);

		project.setText(DEFAULT_PROJECT);
		this.selectProject.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				JFileChooser chooser = new JFileChooser();
				chooser.setDialogTitle(TITLE);
				chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				chooser.setAcceptAllFileFilterUsed(false);
				chooser.setCurrentDirectory(new File(project.getText()));

				int returnVal = chooser.showOpenDialog(getThis());
				if (returnVal == JFileChooser.APPROVE_OPTION) {
					project.setText(chooser.getCurrentDirectory().getPath());

					refreshMiner();
				}
			}
		});
		this.mineit.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				selectProject.setEnabled(false);
				tags.setEnabled(false);
				mineit.setEnabled(false);

				Thread t = new Thread(getThis());
				t.start();
			}
		});

		try {
			init();
		} catch (IOException e) {
			e.printStackTrace();

			log("ERROR: " + e.getMessage());
		}
	}

	private void init() throws IOException {
		// load props
		InputStream configStream = Thread.currentThread().getContextClassLoader()
				.getResourceAsStream("resources/config.properties");
		props = new Properties();
		props.load(configStream);
		// setup connection with mongodb
		Connection conn = Connection.getInstance();
		conn.connect(props.getProperty("bd.uri"), props.getProperty("bd.name"));

		refreshMiner();
	}

	@SuppressWarnings("unchecked")
	private void refreshMiner() {
		// (re)create miner
		miner = new RepositoryMiner(getProjectPath(), props.getProperty("miner.temppath"), getProjectName(), "",
				SCMType.GIT);
		// setup miner
		miner.addParser(new JavaParser());
		miner.setMiningListener(this);

		miner.addDirectCodeSmell(new GodClass());
		miner.addDirectCodeSmell(new DataClass());
		miner.addDirectCodeSmell(new BrainClass());
		miner.addDirectCodeSmell(new LongMethod());
		miner.addDirectCodeSmell(new BrainMethod());
		// miner.addDirectCodeSmell(new FeatureEnvy());
		// miner.addDirectCodeSmell(new ComplexMethod());
		// fill branches in selection combo
		ISCM scm = SCMFactory.getSCM(miner.getScm());
		scm.open(getProjectPath());
		
		tags.removeAllItems();
		for (Reference ref : scm.getReferences()) {
			if (ref.getType().equals(ReferenceType.TAG)) {
				tags.addItem(ref.getName());
			}
		}
		
		scm.close();
	}

	@Override
	public void run() {
		try {
			miner.getReferences().clear();
			miner.addReference(tags.getSelectedItem().toString(), ReferenceType.TAG);

			Repository repository = miner.mine();

			log("exporting to ontology...");

			OntoExporter exporter = new OntoExporter();
			exporter.init(this, this, props).export(repository);

			log("end of mining!");
		} catch (Exception e) {
			e.printStackTrace();

			log("ERROR: " + e.getMessage());
		} finally {
			selectProject.setEnabled(true);
			tags.setEnabled(true);
			mineit.setEnabled(true);			
		}
	}

	public void exhibit() {
		pack();
		setVisible(true);
	}

	public String getProjectPath() {
		return project.getText();
	}

	public String getProjectName() {
		String name = getProjectPath();

		int offs = name.lastIndexOf("/");
		name = name.substring(offs + 1, name.length());

		return name;
	}

	@Override
	public Frame log(String message) {
		message = log.getText() + message + "\n";
		log.setText(message);

		return this;
	}

	@Override
	public void notifyMiningStart(String repositoryName) {
		log("Mining " + repositoryName + "...");

	}

	@Override
	public void notifyMiningEnd(String repositoryName) {
		log("Ended mining " + repositoryName);

	}

	@Override
	public void notifyReferencesMiningStart(int referencesQtd) {
		log("Processing " + referencesQtd + " references");
		
		progress.setMaximum(referencesQtd);
		progress.setValue(0);
	}

	@Override
	public void notifyReferencesMiningProgress(String referenceName, ReferenceType referenceType) {
		progress.setValue(progress.getValue() + 1);
		progressinfo.setText(progress.getValue() + " of " + progress.getMaximum());
		
		repaint();
	}

	@Override
	public void notifyReferencesMiningEnd(int referencesQtd) {
	}

	@Override
	public void notifyCommitsMiningStart(String referenceName, ReferenceType referenceType, int commitsQtd) {
		log("Processing " + commitsQtd + " commits");
		
		progress.setMaximum(commitsQtd);
		progress.setValue(0);
	}

	@Override
	public void notifyCommitsMiningProgress(String referenceName, ReferenceType referenceType, String commit) {
		progress.setValue(progress.getValue() + 1);
		progressinfo.setText(progress.getValue() + " of " + progress.getMaximum());
		
		repaint();
	}

	@Override
	public void notifyCommitsMiningEnd(String referenceName, ReferenceType referenceType, int commitsQtd) {
	}

	@Override
	public void notifyWorkingDirectoriesMiningStart(String referenceName, ReferenceType referenceType, int commitsQtd) {
	}

	@Override
	public void notifyWorkingDirectoriesMiningProgress(String referenceName, ReferenceType referenceType,
			String commit) {
	}

	@Override
	public void notifyWorkingDirectoriesMiningEnd(String referenceName, ReferenceType referenceType, int commitsQtd) {
	}

	@Override
	public void notifyDirectCodeAnalysisStart(int commitsQtd) {
		log("Analysing " + commitsQtd + " commits");
		
		progress.setMaximum(commitsQtd);
		progress.setValue(0);
	}

	@Override
	public void notifyDirectCodeAnalysisProgress(String commit, int index, int totalCommits) {
		progress.setValue(index);
		progressinfo.setText(index + " of " + totalCommits);
		
		repaint();
	}

	@Override
	public void notifyDirectCodeAnalysisEnd(int totalCommits) {
	}

	@Override
	public void notifyIndirectCodeAnalysisStart(int totalSnapshots) {
	}

	@Override
	public void notifyIndirectCodeAnalysisProgress(String snapshot, int index, int totalSnapshots) {
	}

	@Override
	public void notifyIndirectCodeAnalysisEnd(int totalSnapshots) {
	}

	@Override
	public void exportStart(int maxSteps) {
		progress.setMaximum(maxSteps);
		progress.setValue(0);
	}

	@Override
	public void exportProgress(int step) {
		progress.setValue(step);
		progressinfo.setText(step + " of " + progress.getMaximum());
		
		repaint();
	}

}
