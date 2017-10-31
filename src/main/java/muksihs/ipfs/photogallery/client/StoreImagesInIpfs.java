package muksihs.ipfs.photogallery.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.http.client.URL;
import com.google.gwt.user.client.Timer;

import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLImageElement;
import elemental2.dom.XMLHttpRequest;
import muksihs.ipfs.photogallery.shared.ImageData;
import muksihs.ipfs.photogallery.shared.Ipfs;
import muksihs.ipfs.photogallery.shared.IpfsGateway;
import muksihs.ipfs.photogallery.shared.IpfsGatewayEntry;
import muksihs.ipfs.photogallery.ui.GlobalEventBus;

public class StoreImagesInIpfs implements GlobalEventBus, ScheduledCommand {
	private final List<ImageData> imageDataUrls;

	public StoreImagesInIpfs(List<ImageData> imageDataUrls) {
		this.imageDataUrls = imageDataUrls;
	}

	private Void defer(ScheduledCommand cmd) {
		Scheduler.get().scheduleDeferred(cmd);
		return null;
	}

	@Override
	public void execute() {
		fireEvent(new Event.StoreImagesStarted());
		PutState state = new PutState();
		state.setHash(Ipfs.EMPTY_DIR);
		state.setIndex(0);
		state.setImages(new ArrayList<>(this.imageDataUrls));
		state.setIndex(0);
		defer(() -> putImage(state));
	}

	private IpfsGatewayEntry putGw = new IpfsGateway().getWritable();

	private String getEncodedName(PutState state) {
		String prefix = zeroPadded(state.getImagesSize(), state.getIndex());
		String encodedName = URL.encode(prefix + "-" + state.getImageData().getName());
		return encodedName;
	}

	private Void putNextImage(PutState state) {
		if (!state.hasNext()) {
			fireEvent(new Event.IpfsLoadDone());
			fireEvent(new Event.SetXhrProgress(0));
			return null;
		}
		state.resetFails();
		defer(() -> putImage(state.next()));
		return null;
	}

	private Void putImage(PutState state) {
		GWT.log("putImage: " + state.getImageData().getName());
		String baseUrl = putGw.getBaseUrl();
		String xhrUrl = baseUrl.replace(":hash", state.getHash()) + "/" + getEncodedName(state);
		XMLHttpRequest xhr = new XMLHttpRequest();
		xhr.upload.onprogress = (e) -> {
			if (e.lengthComputable) {
				fireEvent(new Event.SetXhrProgress(100 * e.loaded / e.total));
			}
		};
		xhr.onloadend = (e) -> putThumb(state, xhr.getResponseHeader(Ipfs.HEADER_IPFS_HASH), xhr.status);
		xhr.open("PUT", xhrUrl, true);
		xhr.send(state.getImageData().getImageData());
		return null;
	}

	private Void putThumb(PutState state, String newHash, double status) {
		if ((int)status == 405) {
			GWT.log("put not supported on this gateway!");
			fireEvent(new Event.AlertMessage("This IPFS gateway does not support uploads via PUT! Please select a different gateway!"));
			return null;
		}
		if ((int)status != 201) {
			GWT.log("putImage failed: " + state.getImageData().getName() + " [" + status + "]");
			retryPutImage(state);
			return null;
		}
		GWT.log("putThumb: " + state.getImageData().getName());
		String baseUrl = putGw.getBaseUrl();
		String xhrUrl = baseUrl.replace(":hash", newHash) + "/thumb/" + getEncodedName(state);
		XMLHttpRequest xhr = new XMLHttpRequest();
		xhr.upload.onprogress = (e) -> {
			if (e.lengthComputable) {
				fireEvent(new Event.SetXhrProgress(100 * e.loaded / e.total));
			}
		};
		xhr.onloadend = (e) -> verifyThumbImage(state, xhr.getResponseHeader(Ipfs.HEADER_IPFS_HASH), xhr.status);
		xhr.open("PUT", xhrUrl, true);
		xhr.send(state.getImageData().getThumbData());
		return null;
	}

	private String zeroPadded(int length, int ix) {
		ix++;
		int zeroCount = String.valueOf((int) length).length();
		int digitCount = String.valueOf((int) ix).length();
		return StringUtils.repeat("0", zeroCount - digitCount) + String.valueOf((int) ix);
	}

	private static class ImgLoadState {
		public int maxFails;
		public boolean loaded;
		public int failCount;

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("ImgLoadState [maxFails=").append(maxFails).append(", loaded=").append(loaded)
					.append(", failCount=").append(failCount).append("]");
			return builder.toString();
		}
	}

	private Void verifyThumbImage(PutState state, String newHash, double status) {
		fireEvent(new Event.SetXhrProgressIndeterminate());
		if ((int)status != 201) {
			GWT.log("putThumb failed: " + state.getImageData().getName() + " [" + status + "]");
			retryPutImage(state);
			return null;
		}
		if (newHash == null || newHash.trim().isEmpty()) {
			DomGlobal.console
					.log("putThumb failed, no ipfs hash: " + state.getImageData().getName() + " [" + status + "]");
			retryPutImage(state);
			return null;
		}
		GWT.log("verifyThumbImage: " + state.getImageData().getName());
		// the first successful IMG GET becomes the assigned URL for both image and
		// thumb
		final HTMLImageElement[] imgs = new HTMLImageElement[4];
		Set<String> already = new HashSet<>();
		ImgLoadState loadState = new ImgLoadState();
		loadState.failCount = 0;
		loadState.loaded = false;
		loadState.maxFails = imgs.length;
		IpfsGateway ipfsGateway = new IpfsGateway();
		for (int iy = 0; iy < imgs.length; iy++) {
			IpfsGatewayEntry fetchGw = ipfsGateway.getAnyReadonly();
			String url = fetchGw.getBaseUrl().replace(":hash", newHash) + "/thumb/" + getEncodedName(state);
			if (already.contains(url)) {
				loadState.maxFails--;
				continue;
			}
			already.add(url);
			final HTMLImageElement img = (HTMLImageElement) DomGlobal.document.createElement("img");
			imgs[iy] = img;
			img.onabort = (e2) -> onImageLoadFail(state, loadState, img);
			img.onerror = (e2) -> onImageLoadFail(state, loadState, img);
			img.onload = (e2) -> thumbImageVerified(fetchGw.getBaseUrl(), newHash, state, loadState, imgs, img);
			/*
			 * Don't start loading imgs until event hooks and max fail count are set!
			 */
			defer(() -> img.setAttribute("src", url));
		}
		return null;
	}

	private Void thumbImageVerified(String fetchGwUrl, String newHash, PutState state, ImgLoadState loadState,
			HTMLImageElement[] imgs, HTMLImageElement img) {
		if (loadState.loaded) {
			return null;
		}
		loadState.loaded = true;
		GWT.log("thumbImageVerified: " + state.getImageData().getName());
		state.getImageData().setThumbUrl(img.src);
		state.getImageData().setImageUrl(fetchGwUrl.replace(":hash", newHash) + "/" + getEncodedName(state));
		for (HTMLImageElement i : imgs) {
			if (i == null) {
				continue;
			}
			if (i.hasAttribute("src")) {
				i.removeAttribute("src");
			}
		}
		state.setHash(newHash);
		fireEvent(new Event.AddToPreviewPanel(state.getImageData()));
		fireEvent(new Event.SetProgress((1 + state.getIndex()) * 100 / state.getImagesSize()));
		defer(() -> putNextImage(state));
		return null;
	}

	private Void onImageLoadFail(PutState state, ImgLoadState loadState, HTMLImageElement img) {
		GWT.log("onImageLoadFail: " + loadState.toString());
		if (loadState.loaded) {
			return null;
		}
		if (img.hasAttribute("src")) {
			img.removeAttribute("src");
		}
		loadState.failCount++;
		if (loadState.failCount >= loadState.maxFails) {
			retryPutImage(state); // try again
			return null;
		}
		return null;
	}

	private Timer timer;
	private void retryPutImage(PutState state) {
		state.incFails();
		if (state.getPutFails()>5) {
			fireEvent(new Event.AlertMessage("Too Many Upload Failures!"));
			fireEvent(new Event.AlertMessage("Aborting!"));
			return;
		}
		GWT.log("retryPutImage: " + state.getImageData().getName());
		if (timer != null) {
			GWT.log("timer was running already: " + timer.isRunning());
			timer.cancel();
		}
		timer = new Timer() {
			@Override
			public void run() {
				defer(()->putImage(state));
				timer = null;
			}
		};
		timer.schedule(1000);
	}
}
