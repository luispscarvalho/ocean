package br.org.ocean.exporter;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.bson.Document;
import org.repositoryminer.codesmell.CodeSmellId;
import org.repositoryminer.model.ClassCodeSmell;
import org.repositoryminer.model.CodeSmell;
import org.repositoryminer.model.Commit;
import org.repositoryminer.model.Contributor;
import org.repositoryminer.model.Diff;
import org.repositoryminer.model.MethodCodeSmell;
import org.repositoryminer.model.Metric;
import org.repositoryminer.model.Repository;
import org.repositoryminer.persistence.handler.CommitDocumentHandler;
import org.repositoryminer.persistence.handler.DirectCodeAnalysisDocumentHandler;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.PrefixManager;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

import br.org.ocean.IExportProgressListener;
import br.org.ocean.ILogger;

public class OntoExporter {
	private static final PrefixManager smellsPrefix = new DefaultPrefixManager(
			"http://www.semanticweb.org/resys/ontologies/2016/1/codesmells#");
	private static final PrefixManager metricsPrefix = new DefaultPrefixManager(
			"http://www.semanticweb.org/resys/ontologies/2016/1/metrics#");
	private static final PrefixManager repositoriesPrefix = new DefaultPrefixManager(
			"http://www.semanticweb.org/resys/ontologies/2016/1/repositories#");
	private static final PrefixManager oceanPrefix = new DefaultPrefixManager(
			"http://www.semanticweb.org/resys/ontologies/2016/2/ocean#");

	// globals
	private OWLOntologyManager manager;
	private OWLOntology onto;
	private OWLDataFactory factory;

	private IExportProgressListener listener;
	private ILogger logger;
	private String path;

	private Map<String, OWLIndividual> addedCommitters;

	public OntoExporter init(ILogger logger, IExportProgressListener listener, Properties properties) throws OWLOntologyCreationException {
		this.logger = logger;
		this.listener = listener;
		addedCommitters = new HashMap<String, OWLIndividual>();
		manager = OWLManager.createOWLOntologyManager();

		path = properties.getProperty("ontos.ocean.path");
		// load imported/referenced ontologies
		manager.getIRIMappers()
				.add(new SimpleIRIMapper(IRI.create("http://www.semanticweb.org/resys/ontologies/2016/1/codesmells"),
						IRI.create("file:////" + path + "/codesmells.owl")));
		manager.getIRIMappers()
				.add(new SimpleIRIMapper(IRI.create("http://www.semanticweb.org/resys/ontologies/2016/1/repositories"),
						IRI.create("file:////" + path + "/repositories.owl")));
		manager.getIRIMappers()
				.add(new SimpleIRIMapper(IRI.create("http://www.semanticweb.org/resys/ontologies/2016/1/metrics"),
						IRI.create("file:////" + path + "/metrics.owl")));
		// load ocean ontology
		onto = manager.loadOntologyFromOntologyDocument(IRI.create("file:////" + path + "/ocean.owl"));

		// next time path will be used to export the ontology
		path = properties.getProperty("ontos.output.path");

		return this;
	}

	public void export(Repository repository) throws FileNotFoundException, OWLOntologyStorageException {
		// populate the ontology
		populate(repository);
		// export to new onto file
		String filePath = path + "/ocean_" + generateUid() + ".owl";
		java.io.File file = new java.io.File(filePath);

		FileOutputStream outStream = new FileOutputStream(file);
		manager.saveOntology(onto, new StreamDocumentTarget(outStream));

		logger.log("new ontology saved as '" + filePath + "'");
	}

	private void populate(Repository repository) {
		factory = manager.getOWLDataFactory();
		// adds a repository individual
		OWLClass repo = factory.getOWLClass(":Repository", repositoriesPrefix);
		OWLNamedIndividual repositoryInd = factory.getOWLNamedIndividual(generateUid(), oceanPrefix);
		OWLClassAssertionAxiom repositoryAssertion = factory.getOWLClassAssertionAxiom(repo, repositoryInd);
		manager.addAxiom(onto, repositoryAssertion);
		// name of the project
		OWLDataProperty nameValue = factory.getOWLDataProperty("name", repositoriesPrefix);
		OWLDataPropertyAssertionAxiom nameAssertion = factory.getOWLDataPropertyAssertionAxiom(nameValue, repositoryInd,
				repository.getName());
		manager.addAxiom(onto, nameAssertion);
		// adds all commits from the repository
		populateCommits(repository);
	}

	private void populateCommits(Repository repository) {
		CommitDocumentHandler handler = new CommitDocumentHandler();
		// retrieving commits from the repository
		List<Document> commitDocs = handler.findByRepository(repository.getId());
		List<Commit> commits = Commit.parseDocuments(commitDocs);

		listener.exportStart(commits.size());
		
		int step = 0;
		for (Commit commit : commits) {
			listener.exportProgress(++step);
			
			// adding only commits that have produced smells
			Map<String, List<CodeSmell>> smells = getSmells(commit);
			if (!smells.isEmpty()) {
				Contributor contrib = commit.getCommitter();
				// not adding the same committer twice
				String email = contrib.getEmail();
				email = email.replaceAll("@", "_at_").replaceAll(" ", "_");
				OWLIndividual committer;
				if (!addedCommitters.containsKey(email)) {
					committer = addCommitter(contrib, email);
				} else {
					committer = addedCommitters.get(email);
				}
				// creating and binding individuals
				OWLIndividual commitInd = addCommit(commit, committer);
				addCodeSmells(smells, commitInd);
			}
		}
	}

	private OWLIndividual addCommitter(Contributor committer, String email) {
		// adds committer if not added
		OWLClass committerClazz = factory.getOWLClass(":Committer", repositoriesPrefix);
		OWLIndividual committerIndiv = factory.getOWLNamedIndividual(email, oceanPrefix);
		OWLClassAssertionAxiom committerAssertion = factory.getOWLClassAssertionAxiom(committerClazz, committerIndiv);
		manager.addAxiom(onto, committerAssertion);
		// name of the committer
		OWLDataProperty nameValue = factory.getOWLDataProperty("name", repositoriesPrefix);
		OWLDataPropertyAssertionAxiom measuredValueAssertion = factory.getOWLDataPropertyAssertionAxiom(nameValue,
				committerIndiv, committer.getName());
		manager.addAxiom(onto, measuredValueAssertion);
		// adding new committer to avois duplication
		addedCommitters.put(email, committerIndiv);

		return committerIndiv;
	}

	private OWLIndividual addCommit(Commit commit, OWLIndividual committerInd) {
		// adds a commit individual
		OWLClass commitClazz = factory.getOWLClass(":Commit", repositoriesPrefix);
		OWLIndividual commitInd = factory.getOWLNamedIndividual(generateUid(), oceanPrefix);
		OWLClassAssertionAxiom commitAssertion = factory.getOWLClassAssertionAxiom(commitClazz, commitInd);
		manager.addAxiom(onto, commitAssertion);
		// date of the commit
		OWLDataProperty dateValue = factory.getOWLDataProperty("datetime", repositoriesPrefix);
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
		String commitDate = dateFormat.format(commit.getCommitDate());
		OWLDataPropertyAssertionAxiom dateValueAssertion = factory.getOWLDataPropertyAssertionAxiom(dateValue,
				commitInd, commitDate);
		manager.addAxiom(onto, dateValueAssertion);
		// binds the commit to the committer
		OWLObjectProperty hasCommited = factory.getOWLObjectProperty("hasCommited", repositoriesPrefix);
		OWLObjectPropertyAssertionAxiom hasCommitedAssertion = factory.getOWLObjectPropertyAssertionAxiom(hasCommited,
				committerInd, commitInd);
		manager.addAxiom(onto, hasCommitedAssertion);

		return commitInd;
	}

	private void addCodeSmells(Map<String, List<CodeSmell>> smellsByClass, OWLIndividual commitInd) {
		for (Entry<String, List<CodeSmell>> e : smellsByClass.entrySet()) {
			List<CodeSmell> smells = e.getValue();
			for (CodeSmell smell : smells) {
				OWLIndividual smellInd = null;
				if (smell instanceof ClassCodeSmell) {
					ClassCodeSmell cSmell = (ClassCodeSmell) smell;
					smellInd = addClassCodeSmell(e.getKey(), cSmell);
				} else if (smell instanceof MethodCodeSmell) {
					MethodCodeSmell mSmell = (MethodCodeSmell) smell;
					smellInd = addMethodCodeSmell(e.getKey(), mSmell);
				}
				// links commit to smell (hasIntroduced)
				OWLObjectProperty hasIntroduced = factory.getOWLObjectProperty("hasIntroduced", oceanPrefix);
				OWLObjectPropertyAssertionAxiom hasIntroducedAssertion = factory.getOWLObjectPropertyAssertionAxiom(hasIntroduced,
						commitInd, smellInd);
				manager.addAxiom(onto, hasIntroducedAssertion);
			}
		}
	}
	
	private OWLIndividual addCodeSmell(String clazzName, CodeSmellId smellId, List<Metric> metrics) {
		// add a "class smell"
		OWLClass smellClazz = factory.getOWLClass(":" + smellId.getLabel(), smellsPrefix);
		OWLIndividual smellInd = factory.getOWLNamedIndividual(generateUid() + "_" + smellId.getLabel(),
				oceanPrefix);
		OWLClassAssertionAxiom smellAssertion = factory.getOWLClassAssertionAxiom(smellClazz, smellInd);
		manager.addAxiom(onto, smellAssertion);
		// and the location of the smell in the source code
		OWLDataProperty foundInValue = factory.getOWLDataProperty("foundIn", oceanPrefix);
		OWLDataPropertyAssertionAxiom foundInValueAssertion = factory.getOWLDataPropertyAssertionAxiom(foundInValue,
				smellInd, clazzName);
		manager.addAxiom(onto, foundInValueAssertion);
		// add all metrics used to detect the smell
		for (Metric metric : metrics) {
			addMetric(smellInd, metric.getName(), metric.getValue());
		}

		return smellInd;
	}

	private OWLIndividual addClassCodeSmell(String clazzName, ClassCodeSmell codeSmell) {
		return addCodeSmell(clazzName, codeSmell.getSmellId(), codeSmell.getMetrics());
	}

	private OWLIndividual addMethodCodeSmell(String clazzName, MethodCodeSmell codeSmell) {
		// add a "method smell"
		OWLIndividual smellInd = addCodeSmell(clazzName, codeSmell.getSmellId(), codeSmell.getMetrics());
		// and the location of the smell in the source code
		OWLDataProperty foundInValue = factory.getOWLDataProperty("foundIn", oceanPrefix);
		OWLDataPropertyAssertionAxiom foundInValueAssertion = factory.getOWLDataPropertyAssertionAxiom(foundInValue,
				smellInd, codeSmell.getSignature());
		manager.addAxiom(onto, foundInValueAssertion);

		return smellInd;
	}

	private void addMetric(OWLIndividual codeSmellInd, String metricName, double value) {
		// BRAIN_METHOD is a smell but also a metric of the BRAIN_CLASS smell
		// we are going to map it as BM when used as metric
		if (metricName.equals("BRAIN_METHOD")) {
			metricName = "BM";
		}
		
		OWLClass metric = factory.getOWLClass(":" + metricName, metricsPrefix);
		OWLNamedIndividual metricInd = factory.getOWLNamedIndividual(generateUid() + "_" + metricName, oceanPrefix);
		OWLClassAssertionAxiom metricAssertion = factory.getOWLClassAssertionAxiom(metric, metricInd);
		manager.addAxiom(onto, metricAssertion);

		OWLDataProperty measuredValue = factory.getOWLDataProperty("measuredValue", metricsPrefix);
		OWLDataPropertyAssertionAxiom measuredValueAssertion = factory.getOWLDataPropertyAssertionAxiom(measuredValue,
				metricInd, value);
		manager.addAxiom(onto, measuredValueAssertion);

		OWLObjectProperty hasMeasured = factory.getOWLObjectProperty("hasMeasured", oceanPrefix);
		OWLObjectPropertyAssertionAxiom hasMeasuredAssertion = factory.getOWLObjectPropertyAssertionAxiom(hasMeasured,
				codeSmellInd, metricInd);
		manager.addAxiom(onto, hasMeasuredAssertion);
	}

	@SuppressWarnings("unchecked")
	private Map<String, List<CodeSmell>> getSmells(Commit commit) {
		Map<String, List<CodeSmell>> smellsByClass = new HashMap<String, List<CodeSmell>>();

		DirectCodeAnalysisDocumentHandler handler = new DirectCodeAnalysisDocumentHandler();

		List<Diff> diffs = commit.getDiffs();
		for (Diff diff : diffs) {
			Document document = handler.getClasses(diff.getHash(), commit.getId());
			if (document != null) {
				List<Document> clazzDocs = (List<Document>) document.get("classes");

				for (Document clazzDoc : clazzDocs) {
					List<CodeSmell> smells = CodeSmell.parseDocument(clazzDoc);
					if ((smells != null) && (!smells.isEmpty())) {
						String clazzName = clazzDoc.getString("name");
						smellsByClass.put(clazzName, smells);
					}
				}
			}
		}

		return smellsByClass;
	}

	private String generateUid() {
		SecureRandom random = new SecureRandom();
		String uid = new BigInteger(130, random).toString(32);

		return uid;
	}

}
