package br.org.ocean;

import br.org.ocean.ui.Frame;

public class Executor {

	private static Frame frame;

	public static void main(String[] args) throws Exception {
		frame = new Frame();
		frame.configure();
		
		frame.exhibit();
	}
}
