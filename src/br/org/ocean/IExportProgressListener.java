package br.org.ocean;

public interface IExportProgressListener {
	
	public void exportStart(int maxSteps);

	public void exportProgress(int step);

}
