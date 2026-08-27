package com.adam.server.web;

import com.adam.server.scan.ScanService;
import com.adam.server.scan.ScanSnapshot;
import com.adam.server.sdd.SddScan;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @GetMapping(value = "/scan/last", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScanSnapshot last() {
        return scanService.last();
    }

    @PostMapping(value = "/scan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScanSnapshot scan() {
        return scanService.scan();
    }

    @GetMapping(value = "/signals", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SddScan> signals() {
        return scanService.signals();
    }
}
